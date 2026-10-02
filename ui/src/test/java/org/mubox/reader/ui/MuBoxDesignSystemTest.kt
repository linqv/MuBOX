package org.mubox.reader.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import org.mubox.reader.core.model.settings.AppColorPalette
import org.mubox.reader.core.model.media.MediaKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MuBoxDesignSystemTest {
    @Test
    fun lightAndDarkUseAnthropicBrandFoundation() {
        val light = muBoxColorSchemeFor(AppColorPalette.MU_BOX_LIGHT)
        val dark = muBoxColorSchemeFor(AppColorPalette.MU_BOX_DARK)

        assertEquals(Color(0xFFFAF9F5), light.background)
        assertEquals(Color(0xFF141413), light.onBackground)
        assertEquals(Color(0xFF141413), dark.background)
        assertEquals(Color(0xFFFAF9F5), dark.onBackground)
        assertEquals(Color(0xFFD97757), light.primary)
        assertEquals(light.primary, dark.primary)
        assertEquals(Color(0xFF141413), light.onPrimary)
        assertEquals(light.onPrimary, dark.onPrimary)
        assertEquals(Color(0xFFE8E6DC), light.surfaceContainerHighest)
    }

    @Test
    fun muBoxLightDerivesFoundationColorRoles() {
        val colorScheme = muBoxColorSchemeFor(AppColorPalette.MU_BOX_LIGHT)
        val colors = muBoxColorsFor(colorScheme)

        assertEquals(colorScheme.background, colors.background)
        assertEquals(colorScheme.surfaceContainer, colors.panel)
        assertEquals(colorScheme.surfaceContainerHigh, colors.panelHigh)
        assertEquals(colorScheme.primary, colors.mediaAccent)
        assertEquals(colorScheme.secondary, colors.comicAccent)
        assertEquals(colorScheme.tertiary, colors.statusAccent)
        assertEquals(Color(0xFF546641), colors.success)

        assertTrue("light background should stay bright", colors.background.luminance() > 0.85f)
        assertTrue("panel should be warm tinted container", colors.panel.luminance() < colors.background.luminance())
        assertTrue("panelHigh should layer below panel", colors.panelHigh.luminance() < colors.panel.luminance())
        assertTrue("text should stay dark on light surfaces", colors.text.luminance() < 0.20f)
    }

    @Test
    fun muBoxDarkDerivesFoundationColorRoles() {
        val colorScheme = muBoxColorSchemeFor(AppColorPalette.MU_BOX_DARK)
        val colors = muBoxColorsFor(colorScheme)

        assertTrue(colors.isMuBoxDark)
        assertEquals(colorScheme.background, colors.background)
        assertEquals(colorScheme.surfaceContainer, colors.panel)
        assertEquals(colorScheme.surfaceContainerHigh, colors.panelHigh)
        assertEquals(MuBoxDarkTokens.SurfaceSecondary, colors.surfaceSecondary)
        assertEquals(colorScheme.primary, colors.mediaAccent)
        assertEquals(colorScheme.secondary, colors.comicAccent)
        assertEquals(colorScheme.tertiary, colors.statusAccent)
        assertEquals(Color(0xFF94A97C), colors.success)

        assertTrue("background should stay dark", colors.background.luminance() < 0.05f)
        assertTrue("panel should layer above background", colors.panel.luminance() > colors.background.luminance())
        assertTrue("panelHigh should layer above panel", colors.panelHigh.luminance() > colors.panel.luminance())
        assertTrue("text should be readable on dark surfaces", colors.text.luminance() > 0.80f)
    }

    @Test
    fun mediaControlsStayReadableAcrossBothThemes() {
        AppColorPalette.entries.forEach { palette ->
            val colors = muBoxColorsFor(muBoxColorSchemeFor(palette))

            assertEquals(MuBoxDarkTokens.SurfacePrimary, colors.playerSheet)
            assertEquals(MuBoxDarkTokens.SurfacePrimary, colors.playerHud)
            assertTrue("media sheets stay dark", colors.playerSheet.luminance() < 0.05f)
            assertTrue("media controls meet AA", contrastRatio(colors.playerOsdText, colors.playerSheet) >= 4.5f)
            assertTrue("media chips meet AA", contrastRatio(colors.playerOsdText, colors.playerChip) >= 4.5f)
            assertTrue("selected media chips meet AA", contrastRatio(colors.onMediaAccent, colors.playerChipSelected) >= 4.5f)
        }
    }

    @Test
    fun themesUseSolidSurfacesWithoutDecorativeGlows() {
        AppColorPalette.entries.forEach { palette ->
            val colors = muBoxColorsFor(muBoxColorSchemeFor(palette))

            assertEquals(1f, colors.glassSurface.alpha, 0f)
            assertEquals(colors.glassStart, colors.glassEnd)
            assertEquals(Color.Transparent, colors.pageAmbientGlow)
            assertEquals(Color.Transparent, colors.neonGlow)
            assertEquals(Color.Transparent, colors.neonAmbient)
        }
    }

    @Test
    fun muBoxLightAndDarkMeetBodyContrastAA() {
        AppColorPalette.entries.forEach { palette ->
            val scheme = muBoxColorSchemeFor(palette)
            val colors = muBoxColorsFor(scheme)
            val surfaces = listOf(colors.background, colors.panel, colors.panelHigh, colors.surfaceActive)

            surfaces.forEach { surface ->
                assertTrue("body text on $palette surfaces should meet AA", contrastRatio(colors.text, surface) >= 4.5f)
                assertTrue("secondary text on $palette surfaces should meet AA", contrastRatio(colors.muted, surface) >= 4.5f)
                assertTrue("tertiary text on $palette surfaces should meet AA", contrastRatio(colors.textTertiary, surface) >= 4.5f)
                assertTrue("small accent text on $palette surfaces should meet AA", contrastRatio(colors.accentText, surface) >= 4.5f)
                assertTrue("selected outlines on $palette surfaces should meet graphical contrast", contrastRatio(colors.selectedBorder, surface) >= 3f)
                assertTrue("success text on $palette surfaces should meet AA", contrastRatio(colors.success, surface) >= 4.5f)
                assertTrue("info text on $palette surfaces should meet AA", contrastRatio(colors.info, surface) >= 4.5f)
                assertTrue("warning text on $palette surfaces should meet AA", contrastRatio(colors.warning, surface) >= 4.5f)
            }
            assertTrue("soft accent pair should meet AA", contrastRatio(colors.onAccentSoft, colors.accentSoft) >= 4.5f)
            assertTrue("primary action text should meet AA", contrastRatio(colors.onMediaAccent, colors.mediaAccent) >= 4.5f)
            assertTrue("poster chip text should meet AA", contrastRatio(colors.onPosterChip, colors.posterChip) >= 4.5f)
            assertTrue("error text should meet AA", contrastRatio(colors.errorText, colors.errorSurface) >= 4.5f)
            assertTrue("secondary action text should meet AA", contrastRatio(scheme.onSecondary, scheme.secondary) >= 4.5f)
            assertTrue("tertiary action text should meet AA", contrastRatio(scheme.onTertiary, scheme.tertiary) >= 4.5f)
        }
    }

    @Test
    fun editorialTypographyKeepsReadableBodyText() {
        val typography = muBoxTypography()

        assertEquals(FontFamily.Serif, typography.headlineLarge.fontFamily)
        assertEquals(FontFamily.SansSerif, typography.bodyLarge.fontFamily)
        assertEquals(FontFamily.SansSerif, typography.labelLarge.fontFamily)
        assertTrue("body copy needs comfortable line spacing", typography.bodyLarge.lineHeight.value >= typography.bodyLarge.fontSize.value * 1.4f)
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
        assertEquals(12.dp, MuBoxMetrics.PageHorizontalPaddingDp)
        assertEquals(48.dp, MuBoxMetrics.MinTouchTargetDp)
        assertEquals(6.dp, MuBoxMetrics.DenseRowCornerDp)
        assertEquals(8.dp, MuBoxMetrics.PanelCornerDp)
        assertEquals(8.dp, MuBoxMetrics.PlayerPanelCornerDp)
        assertEquals(64.dp, MuBoxMetrics.PlayerCenterControlVisualDp)
        assertEquals(80.dp, MuBoxMetrics.PlayerCenterControlTouchDp)
    }

    @Test
    fun metricsExposeRadiusScaleTokens() {
        assertEquals(4.dp, MuBoxMetrics.RadiusXsDp)
        assertEquals(6.dp, MuBoxMetrics.RadiusSDp)
        assertEquals(8.dp, MuBoxMetrics.RadiusMDp)
        assertEquals(10.dp, MuBoxMetrics.RadiusLDp)
        assertEquals(12.dp, MuBoxMetrics.RadiusXlDp)
    }

    private fun contrastRatio(foreground: Color, background: Color): Float {
        val lighter = maxOf(foreground.luminance(), background.luminance())
        val darker = minOf(foreground.luminance(), background.luminance())
        return (lighter + 0.05f) / (darker + 0.05f)
    }
}
