package com.example.comicdav.feature.videolibrary

import com.example.comicdav.core.model.settings.AppColorPalette
import com.example.comicdav.data.videolibrary.LocalVideoSource
import com.example.comicdav.data.videolibrary.VideoLibraryItem
import com.example.comicdav.data.videolibrary.VideoLibraryItemWithSources
import com.example.comicdav.data.videolibrary.VideoSourceType
import com.example.comicdav.data.videolibrary.WebDavVideoSource
import com.example.comicdav.ui.comicDavColorSchemeFor
import com.example.comicdav.ui.muBoxColorsFor
import org.junit.Assert.assertEquals
import org.junit.Test

class VideoLibraryScreenTest {
    @Test
    fun screenColorsUseThemePaletteRoles() {
        val highContrast = comicDavColorSchemeFor(AppColorPalette.HIGH_CONTRAST, darkTheme = false)
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
    fun videoLibraryUsesVideoPosterKind() {
        assertEquals(com.example.comicdav.ui.MuBoxPosterKind.Video, videoLibraryPosterKind())
    }

    @Test
    fun countLabelShowsEmptyAndNonEmptyCounts() {
        assertEquals("还没有视频", videoLibraryCountLabel(0))
        assertEquals("2 个视频", videoLibraryCountLabel(2))
    }

    @Test
    fun sourceLabelShowsLocalAndWebDavLabels() {
        assertEquals("本地", videoSourceLabel(VideoSourceType.LOCAL))
        assertEquals("WebDAV", videoSourceLabel(VideoSourceType.WEBDAV))
    }

    @Test
    fun sourceMetaShowsLocalFileName() {
        val item = videoLibraryItem(
            sourceType = VideoSourceType.LOCAL,
            localSource = LocalVideoSource(
                videoLibraryItemId = 1L,
                uri = "content://video/1",
                fileName = "local-movie.mp4",
                size = 100L,
                lastModified = 20L,
            ),
        )

        assertEquals("local-movie.mp4", videoSourceMeta(item))
    }

    @Test
    fun sourceMetaDecodesWebDavRemotePath() {
        val item = videoLibraryItem(
            sourceType = VideoSourceType.WEBDAV,
            webDavSource = WebDavVideoSource(
                videoLibraryItemId = 1L,
                accountId = "account",
                remotePath = "/%E8%A7%86%E9%A2%91/remote-movie.mp4",
                fileName = "remote-movie.mp4",
                size = 100L,
                etag = "etag",
                lastModified = 20L,
            ),
        )

        assertEquals("/视频/remote-movie.mp4", videoSourceMeta(item))
    }

    private fun videoLibraryItem(
        sourceType: VideoSourceType,
        localSource: LocalVideoSource? = null,
        webDavSource: WebDavVideoSource? = null,
    ): VideoLibraryItemWithSources {
        return VideoLibraryItemWithSources(
            item = VideoLibraryItem(
                id = 1L,
                title = "movie",
                displayName = "movie.mp4",
                sourceType = sourceType,
                addedAt = 100L,
            ),
            localSource = localSource,
            webDavSource = webDavSource,
        )
    }
}
