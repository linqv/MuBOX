package com.example.comicdav.ui

import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.dp
import com.example.comicdav.core.model.settings.AppColorPalette
import com.example.comicdav.core.model.media.MediaKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MuBoxDesignSystemTest {
    @Test
    fun defaultPaletteMapsToMuBoxFoundationColorRoles() {
        val colorScheme = comicDavColorSchemeFor(AppColorPalette.DEFAULT)
        val colors = muBoxColorsFor(colorScheme)

        assertEquals(colorScheme.background, colors.background)
        assertEquals(colorScheme.surfaceContainer, colors.panel)
        assertEquals(colorScheme.surfaceContainerHigh, colors.panelHigh)
        assertEquals(colorScheme.primary, colors.mediaAccent)
        assertEquals(colorScheme.secondary, colors.comicAccent)
        assertEquals(colorScheme.tertiary, colors.statusAccent)

        assertTrue("background should stay dark", colors.background.luminance() < 0.05f)
        assertTrue("panel should layer above background", colors.panel.luminance() > colors.background.luminance())
        assertTrue("panelHigh should layer above panel", colors.panelHigh.luminance() > colors.panel.luminance())
        assertTrue("text should be readable on dark surfaces", colors.text.luminance() > 0.70f)
    }

    @Test
    fun mediaKindLabelsAreLocalizedForMediaFoundationRows() {
        assertEquals("文件夹", muBoxMediaKindLabel(MediaKind.Directory))
        assertEquals("漫画文件", muBoxMediaKindLabel(MediaKind.Comic))
        assertEquals("视频文件", muBoxMediaKindLabel(MediaKind.Video))
        assertEquals("字幕文件", muBoxMediaKindLabel(MediaKind.Subtitle))
        assertEquals("音频文件", muBoxMediaKindLabel(MediaKind.Audio))
        assertEquals("文件", muBoxMediaKindLabel(MediaKind.Unknown))
    }

    @Test
    fun posterAspectRatiosSeparateComicAndVideoSurfaces() {
        assertEquals(0.72f, muBoxPosterAspectRatio(MuBoxPosterKind.Comic), 0.0001f)
        assertEquals(16f / 9f, muBoxPosterAspectRatio(MuBoxPosterKind.Video), 0.0001f)
    }

    @Test
    fun metricsExposeFoundationSizingTokens() {
        assertEquals(44.dp, MuBoxMetrics.MinTouchTargetDp)
        assertEquals(10.dp, MuBoxMetrics.DenseRowCornerDp)
        assertEquals(16.dp, MuBoxMetrics.PlayerPanelCornerDp)
        assertEquals(64.dp, MuBoxMetrics.PlayerCenterControlVisualDp)
        assertEquals(80.dp, MuBoxMetrics.PlayerCenterControlTouchDp)
    }

}
