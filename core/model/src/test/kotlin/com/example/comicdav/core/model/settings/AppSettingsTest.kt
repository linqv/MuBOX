package com.example.comicdav.core.model.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AppSettingsTest {
    @Test
    fun groupCopyChangesOnlyTheSelectedDomain() {
        val original = AppSettings(
            appearance = AppearanceSettings(colorPalette = AppColorPalette.NIGHT),
            storage = StorageSettings(diskCacheLimitMb = 3072),
            video = VideoSettings(videoResumeEnabled = false),
            history = HistorySettings(historyMaxRecords = 500),
        )

        val updated = original.copy(
            reader = original.reader.copy(autoPageEnabled = true),
        )

        assertTrue(updated.reader.autoPageEnabled)
        assertEquals(original.appearance, updated.appearance)
        assertEquals(original.storage, updated.storage)
        assertEquals(original.video, updated.video)
        assertEquals(original.history, updated.history)
    }
}
