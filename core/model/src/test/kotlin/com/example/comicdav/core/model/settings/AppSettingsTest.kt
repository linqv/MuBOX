package com.example.comicdav.core.model.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppSettingsTest {
    @Test
    fun legacyFlatConstructionPopulatesCanonicalGroups() {
        val settings = AppSettings(
            readingDirection = ReadingDirection.RIGHT_TO_LEFT,
            colorPalette = AppColorPalette.SEPIA,
            diskCacheLimitMb = 2048,
            videoResumeEnabled = false,
            historyRetentionDays = 180,
        )

        assertEquals(ReadingDirection.RIGHT_TO_LEFT, settings.reader.readingDirection)
        assertEquals(AppColorPalette.SEPIA, settings.appearance.colorPalette)
        assertEquals(2048, settings.storage.diskCacheLimitMb)
        assertFalse(settings.video.videoResumeEnabled)
        assertEquals(180, settings.history.historyRetentionDays)
    }

    @Test
    fun groupCopyChangesOnlyTheSelectedDomain() {
        val original = AppSettings(
            colorPalette = AppColorPalette.NIGHT,
            diskCacheLimitMb = 3072,
            videoResumeEnabled = false,
            historyMaxRecords = 500,
        )

        val updated = original.copy(
            reader = original.reader.copy(autoPageEnabled = true),
        )

        assertTrue(updated.reader.autoPageEnabled)
        assertEquals(original.appearance, updated.appearance)
        assertEquals(original.storage, updated.storage)
        assertEquals(original.video, updated.video)
        assertEquals(original.history, updated.history)
        assertTrue(updated.autoPageEnabled)
    }
}
