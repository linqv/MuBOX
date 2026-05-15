package com.example.comicdav.feature.reader

import org.junit.Assert.assertEquals
import org.junit.Test

class ReaderLoadingProgressTest {
    @Test
    fun fractionIsClampedAndLabelUsesKibibytes() {
        val progress = ReaderLoadingProgress(downloadedBytes = 3 * 1024L, totalBytes = 2 * 1024L)

        assertEquals(1f, progress.fraction)
        assertEquals("Downloading 3 KiB / 2 KiB", progress.label)
    }
}
