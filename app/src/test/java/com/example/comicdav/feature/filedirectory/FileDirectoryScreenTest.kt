package com.example.comicdav.feature.filedirectory

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import com.example.comicdav.data.AppColorPalette
import com.example.comicdav.data.filedirectory.FileDirectorySourceEntity
import com.example.comicdav.data.filedirectory.FileDirectorySourceType
import com.example.comicdav.ui.comicDavColorSchemeFor
import com.example.comicdav.ui.muBoxColorsFor
import com.example.comicdav.video.MediaKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FileDirectoryScreenTest {
    @Test
    fun screenColorsUseThemePaletteRoles() {
        val highContrast = comicDavColorSchemeFor(AppColorPalette.HIGH_CONTRAST)
        val colors = muBoxColorsFor(highContrast)

        assertEquals(highContrast.background, colors.background)
        assertEquals(highContrast.surfaceContainer, colors.panel)
        assertEquals(highContrast.surfaceContainerHigh, colors.panelHigh)
        assertEquals(highContrast.primary, colors.mediaAccent)
        assertEquals(highContrast.onPrimary, colors.onMediaAccent)
        assertEquals(highContrast.onBackground, colors.text)
        assertEquals(highContrast.onSurfaceVariant, colors.muted)
    }

    @Test
    fun sourceBadgeUsesAccessibleContrast() {
        val colors = muBoxColorsFor(comicDavColorSchemeFor(AppColorPalette.DEFAULT))

        assertTrue(
            "source badge contrast should meet AA for small text",
            contrastRatio(colors.onAccentSoft, colors.accentSoft) >= 4.5f,
        )
    }

    @Test
    fun entryTypeContentDescriptionsUseSharedMediaLabels() {
        MediaKind.entries.forEach { mediaKind ->
            assertEquals(
                com.example.comicdav.ui.muBoxMediaKindLabel(mediaKind),
                fileDirectoryEntryTypeContentDescription(mediaKind),
            )
        }
    }

    @Test
    fun comicLongPressActionsAddToLibrary() {
        val comic = FileDirectoryBrowserItem(
            name = "chapter.cbz",
            uri = "content://comic/chapter.cbz",
            isDirectory = false,
        )

        assertEquals(
            listOf(FileDirectoryEntryMenuAction.AddToLibrary),
            fileDirectoryEntryLongPressActions(comic),
        )
    }

    @Test
    fun videoLongPressActionsAddToVideoLibrary() {
        val video = FileDirectoryBrowserItem(
            name = "movie.mp4",
            uri = "content://video/movie.mp4",
            isDirectory = false,
        )

        assertEquals(
            listOf(FileDirectoryEntryMenuAction.AddToVideoLibrary),
            fileDirectoryEntryLongPressActions(video),
        )
    }

    @Test
    fun webDavSourceSubtitleDecodesPercentEncodedPath() {
        val source = FileDirectorySourceEntity(
            displayName = "漫画",
            sourceType = FileDirectorySourceType.WEBDAV,
            webDavPath = "/%E6%BC%AB%E7%94%BB/%E8%A7%86%E9%A2%91/",
            addedAt = 100L,
        )

        assertEquals("/漫画/视频/", fileDirectorySourceSubtitle(source))
    }

    private fun contrastRatio(foreground: Color, background: Color): Float {
        val lighter = maxOf(foreground.luminance(), background.luminance())
        val darker = minOf(foreground.luminance(), background.luminance())
        return (lighter + 0.05f) / (darker + 0.05f)
    }
}
