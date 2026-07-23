package com.example.comicdav.video

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoPlaybackMemoryBudgetTest {
    @Test
    fun budgetUsesOneQuarterOfMemoryClassAndSharesOneTotalCap() {
        val budget = VideoPlaybackMemoryBudget.fromMemoryClassMb(512)

        assertEquals(128L * 1024L * 1024L, budget.totalBytes)
        assertEquals(
            budget.totalBytes,
            budget.mpvForwardBytes + budget.mpvBackwardBytes + budget.proxyBytes,
        )
    }

    @Test
    fun budgetIsBoundedOnSmallAndLargeHeaps() {
        val small = VideoPlaybackMemoryBudget.fromMemoryClassMb(128)
        val large = VideoPlaybackMemoryBudget.fromMemoryClassMb(2048)

        assertEquals(48L * 1024L * 1024L, small.totalBytes)
        assertEquals(192L * 1024L * 1024L, large.totalBytes)
        assertTrue(small.proxyBytes > 0L)
    }
}
