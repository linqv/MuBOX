package org.mubox.reader.feature.videolibrary

import org.mubox.reader.core.model.settings.AppColorPalette
import org.mubox.reader.ui.muBoxColorSchemeFor
import org.mubox.reader.ui.muBoxColorsFor
import org.junit.Assert.assertEquals
import org.junit.Test

class VideoLibraryScreenTest {
    @Test
    fun screenColorsUseThemePaletteRoles() {
        val highContrast = muBoxColorSchemeFor(AppColorPalette.HIGH_CONTRAST, darkTheme = false)
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
        assertEquals(org.mubox.reader.ui.MuBoxPosterKind.Video, videoLibraryPosterKind())
    }

    @Test
    fun countLabelShowsEmptyAndNonEmptyCounts() {
        assertEquals("还没有视频", videoLibraryCountLabel(0))
        assertEquals("2 个视频", videoLibraryCountLabel(2))
    }
}
