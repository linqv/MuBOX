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

    @Test
    fun diskCacheLimitIsCoercedIntoSupportedRange() {
        assertEquals(1, coerceDiskCacheLimitGb(0))
        assertEquals(5, coerceDiskCacheLimitGb(9))
        assertEquals(3, coerceDiskCacheLimitGb(3))
    }

    @Test
    fun diskCacheLimitUsesGibibytesForPageCacheBytes() {
        assertEquals(1_073_741_824L, pageCacheLimitBytesForGb(1))
        assertEquals(5_368_709_120L, pageCacheLimitBytesForGb(5))
    }
}
