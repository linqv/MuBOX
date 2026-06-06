package com.example.comicdav.feature.webdav

import com.example.comicdav.data.AppColorPalette
import com.example.comicdav.network.WebDavException
import com.example.comicdav.network.WebDavItem
import com.example.comicdav.ui.comicDavColorSchemeFor
import com.example.comicdav.ui.muBoxColorsFor
import com.example.comicdav.video.MediaKind
import com.example.comicdav.webdav.webDavDisplayPathLabel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class WebDavBrowserScreenTest {
    @Test
    fun screenColorsUseThemePaletteRoles() {
        val highContrast = comicDavColorSchemeFor(AppColorPalette.HIGH_CONTRAST)
        val colors = muBoxColorsFor(highContrast)

        assertEquals(highContrast.background, colors.background)
        assertEquals(highContrast.surfaceContainer, colors.panel)
        assertEquals(highContrast.surfaceContainerHigh, colors.panelHigh)
        assertEquals(highContrast.primary, colors.mediaAccent)
        assertEquals(highContrast.onBackground, colors.text)
        assertEquals(highContrast.onSurfaceVariant, colors.muted)
    }

    @Test
    fun itemTypeContentDescriptionsUseSharedMediaLabels() {
        MediaKind.entries.forEach { mediaKind ->
            assertEquals(
                com.example.comicdav.ui.muBoxMediaKindLabel(mediaKind),
                webDavItemTypeContentDescription(mediaKind),
            )
        }
    }

    @Test
    fun comicLongPressActionsAddToLibraryAndDownload() {
        val comic = webDavFile(name = "chapter.cbz")

        assertEquals(
            listOf(WebDavFileMenuAction.AddToLibrary, WebDavFileMenuAction.DownloadToLocal),
            webDavItemLongPressActions(comic),
        )
    }

    @Test
    fun videoLongPressActionsAddToVideoLibraryAndDownload() {
        val video = webDavFile(name = "movie.mp4")

        assertEquals(
            listOf(WebDavFileMenuAction.AddToVideoLibrary, WebDavFileMenuAction.DownloadToLocal),
            webDavItemLongPressActions(video),
        )
    }

    @Test
    fun displayPathDecodesUtf8PercentEncodedPath() {
        assertEquals(
            "路径 /漫画/视频/",
            webDavDisplayPathLabel("/%E6%BC%AB%E7%94%BB/%E8%A7%86%E9%A2%91/"),
        )
    }

    @Test
    fun displayPathLeavesInvalidEscapesUsable() {
        assertEquals(
            "路径 /bad%ZZ/",
            webDavDisplayPathLabel("/bad%ZZ/"),
        )
    }

    @Test
    fun invalidWebDavResponseMessageDoesNotExposeParserDetails() {
        val message = webDavConnectionFailureMessage(
            WebDavException.InvalidResponse(
                "Invalid WebDAV PROPFIND response",
                IllegalArgumentException("This parser does not support specification \"Unknown\" version \"0.0\""),
            ),
        )

        assertEquals("服务器返回的不是有效的 WebDAV 目录列表，请检查 WebDAV 地址是否正确", message)
        assertFalse(message.contains("parser", ignoreCase = true))
        assertFalse(message.contains("Unknown"))
    }

    private fun webDavFile(name: String): WebDavItem =
        WebDavItem(
            name = name,
            path = "/$name",
            isDirectory = false,
            size = 1024L,
            etag = null,
            lastModified = null,
        )
}
