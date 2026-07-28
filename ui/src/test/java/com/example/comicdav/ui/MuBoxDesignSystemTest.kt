package com.example.comicdav.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.dp
import com.example.comicdav.core.model.settings.AppColorPalette
import com.example.comicdav.core.model.media.MediaKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MuBoxDesignSystemTest {
    @Test
    fun defaultPaletteFollowsSystemDarkAndLight() {
        val dark = comicDavColorSchemeFor(AppColorPalette.DEFAULT, darkTheme = true)
        val light = comicDavColorSchemeFor(AppColorPalette.DEFAULT, darkTheme = false)

        assertEquals(Color(0xFF000626), dark.background)
        assertEquals(Color(0xFF7567FF), dark.primary)
        assertEquals(Color(0xFFF5F7FB), light.background)
        assertEquals(Color(0xFF176BDE), light.primary)
    }

    @Test
    fun explicitMuBoxPalettesIgnoreSystemFlag() {
        assertEquals(
            Color(0xFFF5F7FB),
            comicDavColorSchemeFor(AppColorPalette.MU_BOX_LIGHT, darkTheme = true).background,
        )
        assertEquals(
            Color(0xFF000626),
            comicDavColorSchemeFor(AppColorPalette.MU_BOX_DARK, darkTheme = false).background,
        )
    }

    @Test
    fun legacyPalettesKeepTheirExistingSchemes() {
        val cinemaDark = comicDavColorSchemeFor(AppColorPalette.CINEMA_DARK, darkTheme = false)
        val adwaitaLight = comicDavColorSchemeFor(AppColorPalette.ADWAITA_LIGHT, darkTheme = true)
        val sepia = comicDavColorSchemeFor(AppColorPalette.SEPIA, darkTheme = true)
        val highContrast = comicDavColorSchemeFor(AppColorPalette.HIGH_CONTRAST, darkTheme = true)

        assertEquals(Color(0xFF050A14), cinemaDark.background)
        assertEquals(Color.White, adwaitaLight.surface)
        assertEquals(Color(0xFFFAF3E0), sepia.background)
        assertEquals(Color.Black, highContrast.onSurface)
    }

    @Test
    fun muBoxLightDerivesFoundationColorRoles() {
        val colorScheme = comicDavColorSchemeFor(AppColorPalette.MU_BOX_LIGHT, darkTheme = false)
        val colors = muBoxColorsFor(colorScheme)

        assertEquals(colorScheme.background, colors.background)
        assertEquals(colorScheme.surfaceContainer, colors.panel)
        assertEquals(colorScheme.surfaceContainerHigh, colors.panelHigh)
        assertEquals(colorScheme.primary, colors.mediaAccent)
        assertEquals(colorScheme.secondary, colors.comicAccent)
        assertEquals(colorScheme.tertiary, colors.statusAccent)
        assertEquals(Color(0xFF287A4B), colors.success)

        assertTrue("light background should stay bright", colors.background.luminance() > 0.85f)
        assertTrue("panel should layer above background", colors.panel.luminance() > colors.background.luminance())
        assertTrue("panelHigh should layer below panel", colors.panelHigh.luminance() < colors.panel.luminance())
        assertTrue("text should stay dark on light surfaces", colors.text.luminance() < 0.20f)
    }

    @Test
    fun muBoxDarkDerivesFoundationColorRoles() {
        val colorScheme = comicDavColorSchemeFor(AppColorPalette.MU_BOX_DARK, darkTheme = true)
        val colors = muBoxColorsFor(colorScheme)

        assertTrue(colors.isMuBoxDark)
        assertEquals(colorScheme.background, colors.background)
        assertEquals(colorScheme.surfaceContainer, colors.panel)
        assertEquals(colorScheme.surfaceContainerHigh, colors.panelHigh)
        assertEquals(MuBoxDarkTokens.SurfaceSecondary, colors.surfaceSecondary)
        assertEquals(colorScheme.primary, colors.mediaAccent)
        assertEquals(colorScheme.secondary, colors.comicAccent)
        assertEquals(colorScheme.tertiary, colors.statusAccent)
        assertEquals(Color(0xFF44D7A8), colors.success)

        assertTrue("background should stay dark", colors.background.luminance() < 0.05f)
        assertTrue("panel should layer above background", colors.panel.luminance() > colors.background.luminance())
        assertTrue("panelHigh should layer above panel", colors.panelHigh.luminance() > colors.panel.luminance())
        assertTrue("text should be readable on dark surfaces", colors.text.luminance() > 0.85f)
    }

    @Test
    fun muBoxDarkUsesReferencePaletteWithoutAdHocDarkColors() {
        val scheme = comicDavColorSchemeFor(AppColorPalette.MU_BOX_DARK, darkTheme = false)
        val colors = muBoxColorsFor(scheme)

        assertEquals(Color(0xFF000217), colors.backgroundDeep)
        assertEquals(Color(0xFF000626), colors.background)
        assertEquals(Color(0xFF030D2B), colors.backgroundSecondary)
        assertEquals(Color(0xFF081037), colors.backgroundElevated)
        assertEquals(Color(0xFF0B1236), colors.panel)
        assertEquals(Color(0xFF101B41), colors.surfaceSecondary)
        assertEquals(Color(0xFF192147), colors.surfaceHover)
        assertEquals(Color(0xFF202B58), colors.surfaceActive)
        assertEquals(Color(0xFF1E2B52), colors.border)
        assertEquals(Color(0xFF33436F), colors.borderDefault)
        assertEquals(Color(0xFF8178FF), colors.selectedBorder)
        assertEquals(Color(0xFF7567FF), colors.mediaAccent)
        assertEquals(Color(0xFF000217), colors.onMediaAccent)
        assertEquals(Color(0xFF9C5CFF), colors.comicAccent)
        assertEquals(Color(0xFF4D8DFF), colors.accentBlue)
        assertEquals(Color(0xFF58D6FF), colors.accentCyan)
        assertEquals(Color(0xFFF4F5FF), colors.text)
        assertEquals(Color(0xFFADAFC4), colors.muted)
        assertEquals(Color(0xFF79769A), colors.textTertiary)
        assertEquals(Color(0xFF59517E), colors.textDisabled)
        assertEquals(Color(0xFF44D7A8), colors.success)
        assertEquals(Color(0xFFF5B95E), colors.warning)
        assertEquals(Color(0xFFFF6685), scheme.error)
        assertEquals(Color(0xFF58A6FF), colors.info)
        assertEquals(Color(0xB8000217), colors.overlay)
        assertEquals(Color(0xD0150C32), colors.glassSurface)
        assertEquals(Color(0x5258D6FF), colors.glassBorder)
        assertTrue(
            "glass surface should lean violet",
            colors.glassSurface.blue > colors.glassSurface.green &&
                colors.glassSurface.red > colors.glassSurface.green,
        )
    }

    @Test
    fun muBoxLightAndDarkMeetBodyContrastAA() {
        listOf(
            comicDavColorSchemeFor(AppColorPalette.MU_BOX_LIGHT, darkTheme = false),
            comicDavColorSchemeFor(AppColorPalette.MU_BOX_DARK, darkTheme = true),
        ).forEach { scheme ->
            val colors = muBoxColorsFor(scheme)

            assertTrue("body text on background should meet AA", contrastRatio(colors.text, colors.background) >= 4.5f)
            assertTrue("secondary text on panel should meet AA", contrastRatio(colors.muted, colors.panel) >= 4.5f)
            assertTrue("soft accent pair should meet AA", contrastRatio(colors.onAccentSoft, colors.accentSoft) >= 4.5f)
            assertTrue("small accent text should meet AA", contrastRatio(colors.accentText, colors.panel) >= 4.5f)
            assertTrue("primary action text should meet AA", contrastRatio(colors.onMediaAccent, colors.mediaAccent) >= 4.5f)
        }
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
        assertEquals(3f / 4f, muBoxPosterAspectRatio(MuBoxPosterKind.Video), 0.0001f)
    }

    @Test
    fun mediaGridUsesThreeColumns() {
        assertEquals(3, MU_BOX_MEDIA_GRID_COLUMN_COUNT)
    }

    @Test
    fun metricsExposeFoundationSizingTokens() {
        assertEquals(8.dp, MuBoxMetrics.PageHorizontalPaddingDp)
        assertEquals(48.dp, MuBoxMetrics.MinTouchTargetDp)
        assertEquals(10.dp, MuBoxMetrics.DenseRowCornerDp)
        assertEquals(16.dp, MuBoxMetrics.PlayerPanelCornerDp)
        assertEquals(64.dp, MuBoxMetrics.PlayerCenterControlVisualDp)
        assertEquals(80.dp, MuBoxMetrics.PlayerCenterControlTouchDp)
    }

    @Test
    fun metricsExposeRadiusScaleTokens() {
        assertEquals(6.dp, MuBoxMetrics.RadiusXsDp)
        assertEquals(10.dp, MuBoxMetrics.RadiusSDp)
        assertEquals(12.dp, MuBoxMetrics.RadiusMDp)
        assertEquals(16.dp, MuBoxMetrics.RadiusLDp)
        assertEquals(20.dp, MuBoxMetrics.RadiusXlDp)
    }

    private fun contrastRatio(foreground: Color, background: Color): Float {
        val lighter = maxOf(foreground.luminance(), background.luminance())
        val darker = minOf(foreground.luminance(), background.luminance())
        return (lighter + 0.05f) / (darker + 0.05f)
    }
}
