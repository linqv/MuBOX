package com.example.comicdav.core.model.format

import org.junit.Assert.assertEquals
import org.junit.Test

class CacheSizeFormatTest {
    @Test
    fun formatsCacheSizesForSettingsUi() {
        assertEquals("0 B", formatCacheSize(0))
        assertEquals("512 B", formatCacheSize(512))
        assertEquals("1.5 MB", formatCacheSize(1_572_864))
        assertEquals("2.0 GB", formatCacheSize(2_147_483_648))
    }
}
