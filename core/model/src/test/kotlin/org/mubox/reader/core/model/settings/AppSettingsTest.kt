package org.mubox.reader.core.model.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AppSettingsTest {
    @Test
    fun appearanceOffersOnlyLightAndDarkAndDefaultsToLight() {
        assertEquals(listOf(AppColorPalette.MU_BOX_LIGHT, AppColorPalette.MU_BOX_DARK), AppColorPalette.entries)
        assertEquals(AppColorPalette.MU_BOX_LIGHT, AppearanceSettings().colorPalette)
    }

    @Test
    fun persistedPaletteNamesRetainCurrentChoicesAndMigrateLegacyChoices() {
        listOf("MU_BOX_LIGHT", "DEFAULT", "ADWAITA_LIGHT", "SEPIA", "HIGH_CONTRAST").forEach { name ->
            assertEquals(name, AppColorPalette.MU_BOX_LIGHT, AppColorPalette.fromPersistedName(name))
        }
        listOf("MU_BOX_DARK", "ADWAITA_BLUE_GRAY", "ADWAITA_PURPLE", "CINEMA_DARK", "NIGHT").forEach { name ->
            assertEquals(name, AppColorPalette.MU_BOX_DARK, AppColorPalette.fromPersistedName(name))
        }
    }

    @Test
    fun missingOrUnknownPaletteNamesUseRequestedFallback() {
        listOf(null, "", "REMOVED_PALETTE", "mu_box_dark").forEach { name ->
            assertEquals(AppColorPalette.MU_BOX_LIGHT, AppColorPalette.fromPersistedName(name))
            assertEquals(
                AppColorPalette.MU_BOX_DARK,
                AppColorPalette.fromPersistedName(name, fallback = AppColorPalette.MU_BOX_DARK),
            )
        }
    }

    @Test
    fun groupCopyChangesOnlyTheSelectedDomain() {
        val original = AppSettings(
            appearance = AppearanceSettings(colorPalette = AppColorPalette.MU_BOX_DARK),
            storage = StorageSettings(diskCacheLimitMb = 3072),
            video = VideoSettings(videoResumeEnabled = false),
            history = HistorySettings(historyMaxRecords = 500),
            diagnostics = DiagnosticsSettings(logLevel = DiagnosticLogLevel.OFF),
        )

        val updated = original.copy(
            reader = original.reader.copy(autoPageEnabled = true),
        )

        assertTrue(updated.reader.autoPageEnabled)
        assertEquals(original.appearance, updated.appearance)
        assertEquals(original.storage, updated.storage)
        assertEquals(original.video, updated.video)
        assertEquals(original.history, updated.history)
        assertEquals(original.diagnostics, updated.diagnostics)
    }
}
