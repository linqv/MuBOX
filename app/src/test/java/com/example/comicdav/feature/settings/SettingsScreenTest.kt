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
        assertEquals(0, coerceDiskCacheLimitMb(0))
        assertEquals(500, coerceDiskCacheLimitMb(500))
        assertEquals(1024, coerceDiskCacheLimitMb(1024))
        assertEquals(5120, coerceDiskCacheLimitMb(9000))
    }

    @Test
    fun diskCacheLimitUsesMebibytesForPageCacheBytes() {
        assertEquals(0L, pageCacheLimitBytesForMb(0))
        assertEquals(524_288_000L, pageCacheLimitBytesForMb(500))
        assertEquals(1_073_741_824L, pageCacheLimitBytesForMb(1024))
        assertEquals(5_368_709_120L, pageCacheLimitBytesForMb(5120))
    }

    @Test
    fun diskCacheLimitLabelsUseMbAndGb() {
        assertEquals("0 MB", diskCacheLimitLabel(0))
        assertEquals("500 MB", diskCacheLimitLabel(500))
        assertEquals("1 GB", diskCacheLimitLabel(1024))
        assertEquals("5 GB", diskCacheLimitLabel(5120))
    }

    @Test
    fun webDavPrefetchPageCountIsCoercedIntoSupportedOptions() {
        assertEquals(2, coerceWebDavPrefetchPageCount(1))
        assertEquals(4, coerceWebDavPrefetchPageCount(5))
        assertEquals(6, coerceWebDavPrefetchPageCount(7))
        assertEquals(12, coerceWebDavPrefetchPageCount(99))
    }

    @Test
    fun webDavPrefetchPageCountLabelUsesPages() {
        assertEquals("2 页", webDavPrefetchPageCountLabel(2))
        assertEquals("4 页", webDavPrefetchPageCountLabel(4))
        assertEquals("8 页", webDavPrefetchPageCountLabel(8))
        assertEquals("12 页", webDavPrefetchPageCountLabel(12))
    }
}
