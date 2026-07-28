package com.example.comicdav.feature.webdav

import com.example.comicdav.core.model.settings.AppColorPalette
import com.example.comicdav.core.remote.WebDavException
import com.example.comicdav.core.remote.WebDavItem
import com.example.comicdav.ui.comicDavColorSchemeFor
import com.example.comicdav.ui.muBoxColorsFor
import com.example.comicdav.core.model.media.MediaKind
import com.example.comicdav.webdav.webDavDisplayPathLabel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WebDavBrowserScreenTest {
    @Test
    fun screenColorsUseThemePaletteRoles() {
        val highContrast = comicDavColorSchemeFor(AppColorPalette.HIGH_CONTRAST, darkTheme = false)
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
    fun videoThumbnailVersionChangesWithRemoteValidators() {
        val video = WebDavItem(
            name = "movie.mp4",
            path = "/movie.mp4",
            isDirectory = false,
            size = 100L,
            etag = "v1",
            lastModified = 200L,
        )

        assertFalse(
            webDavVideoThumbnailVersion(video) ==
                webDavVideoThumbnailVersion(video.copy(size = 101L)),
        )
        assertFalse(
            webDavVideoThumbnailVersion(video) ==
                webDavVideoThumbnailVersion(video.copy(etag = "v2")),
        )
        assertFalse(
            webDavVideoThumbnailVersion(video) ==
                webDavVideoThumbnailVersion(video.copy(lastModified = 201L)),
        )
    }

    @Test
    fun remoteThumbnailWithoutValidatorsChangesVersionAfterDirectoryRefresh() {
        val video = WebDavItem(
            name = "movie.mp4",
            path = "/movie.mp4",
            isDirectory = false,
            size = 100L,
            etag = null,
            lastModified = null,
        )

        assertNotEquals(
            webDavBrowserVideoThumbnailVersion(video, requestRevision = 1L),
            webDavBrowserVideoThumbnailVersion(video, requestRevision = 2L),
        )
        assertEquals(
            webDavBrowserVideoThumbnailVersion(
                video.copy(etag = "v1"),
                requestRevision = 1L,
            ),
            webDavBrowserVideoThumbnailVersion(
                video.copy(etag = "v1"),
                requestRevision = 2L,
            ),
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
    fun breadcrumbSplitsAndDecodesCurrentWebDavPath() {
        assertEquals(
            listOf("webdav", "漫画", "myy"),
            webDavBreadcrumbLabels("/webdav/%E6%BC%AB%E7%94%BB/myy/"),
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

    @Test
    fun itemClickOpensDirectoriesAndReadsComics() {
        assertEquals(
            WebDavItemClickAction.OpenDirectory,
            webDavItemClickAction(
                WebDavItem("Series", "/Series/", isDirectory = true, size = null, etag = null, lastModified = null),
            ),
        )
        assertEquals(
            WebDavItemClickAction.OpenComic,
            webDavItemClickAction(
                WebDavItem("book.cbz", "/book.cbz", isDirectory = false, size = 12L, etag = "a", lastModified = null),
            ),
        )
    }

    @Test
    fun longPressActionsAreHiddenForDirectories() {
        assertTrue(
            webDavItemLongPressActions(
                WebDavItem("Series", "/Series/", isDirectory = true, size = null, etag = null, lastModified = null),
            ).isEmpty(),
        )
    }

    @Test
    fun directoryRowsDoNotShowContinueBrowsingHint() {
        assertEquals(
            "",
            webDavItemSupportingLabel(
                WebDavItem("Series", "/Series/", isDirectory = true, size = null, etag = null, lastModified = null),
            ),
        )
    }

    @Test
    fun comicRowsOnlyShowSizeWithoutValidators() {
        assertEquals(
            "12 B",
            webDavItemSupportingLabel(
                WebDavItem("book.cbz", "/book.cbz", isDirectory = false, size = 12L, etag = "abc", lastModified = 123L),
            ),
        )
        assertEquals(
            "大小未知",
            webDavItemSupportingLabel(
                WebDavItem("book.cbz", "/book.cbz", isDirectory = false, size = null, etag = "abc", lastModified = 123L),
            ),
        )
    }

    @Test
    fun saveDirectoryActionOnlyShowsInAddPathFlow() {
        assertTrue(shouldShowSaveDirectoryAction(isAddingPath = true))
        assertFalse(shouldShowSaveDirectoryAction(isAddingPath = false))
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
