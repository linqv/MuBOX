package com.example.comicdav.feature.settings

import org.junit.Assert.assertEquals
import org.junit.Test

class SettingsScreenTest {
    @Test
    fun autoPageSpeedIsCoercedIntoSupportedRange() {
        assertEquals(3, coerceAutoPageSpeed(0))
        assertEquals(60, coerceAutoPageSpeed(75))
        assertEquals(12, coerceAutoPageSpeed(12))
    }

    @Test
    fun autoPageIntervalUsesCoercedSpeedInMilliseconds() {
        assertEquals(3_000L, autoPageIntervalMillisForSpeed(0))
        assertEquals(8_000L, autoPageIntervalMillisForSpeed(8))
        assertEquals(60_000L, autoPageIntervalMillisForSpeed(90))
    }
}
