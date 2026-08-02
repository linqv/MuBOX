package org.mubox.reader.feature.reader

import org.mubox.reader.core.ports.PlannedRemoteRange
import kotlinx.coroutines.CancellationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReaderPrefetchCoordinatorTest {
    @Test
    fun regularPagePrefetchUsesExactDesiredWindow() {
        val desired = setOf(4, 5, 6, 7, 8, 9)

        val retained = retainedPagePrefetchWindow(
            pageIndex = 5,
            pageCount = 20,
            forwardPages = 4,
            desiredWindow = desired,
            reason = "viewport",
        )

        assertEquals(desired, retained)
    }

    @Test
    fun continuousPagePrefetchRetainsNearbyInFlightWork() {
        val retained = retainedPagePrefetchWindow(
            pageIndex = 5,
            pageCount = 20,
            forwardPages = 4,
            desiredWindow = setOf(4, 5, 6, 7, 8, 9),
            reason = "continuous_visible",
        )

        assertEquals((3..11).toSet(), retained)
    }

    @Test
    fun coveredRangeSubtractionMergesOverlapAndClipsToRequestedBytes() {
        val missing = subtractCoveredRanges(
            start = 10L,
            endInclusive = 30L,
            coveredRanges = listOf(0L..12L, 15L..18L, 17L..25L, 40L..50L),
        )

        assertEquals(listOf(13L..14L, 26L..30L), missing)
    }

    @Test
    fun coveredRangeSubtractionHandlesLongMaxWithoutOverflow() {
        val missing = subtractCoveredRanges(
            start = Long.MAX_VALUE - 2L,
            endInclusive = Long.MAX_VALUE,
            coveredRanges = listOf((Long.MAX_VALUE - 1L)..Long.MAX_VALUE),
        )

        assertEquals(listOf((Long.MAX_VALUE - 2L)..(Long.MAX_VALUE - 2L)), missing)
    }

    @Test
    fun sameStartRangesMergeFarthestEndPagesAndHighestPriority() {
        val merged = mergeSameStartPlannedRanges(
            listOf(
                plannedRange(start = 100L, endInclusive = 199L, pages = listOf(3), priority = 4),
                plannedRange(start = 100L, endInclusive = 249L, pages = listOf(2, 3), priority = 1),
                plannedRange(start = 300L, endInclusive = 399L, pages = listOf(8), priority = 2),
            ),
        )

        assertEquals(
            listOf(
                plannedRange(start = 100L, endInclusive = 249L, pages = listOf(2, 3), priority = 1),
                plannedRange(start = 300L, endInclusive = 399L, pages = listOf(8), priority = 2),
            ),
            merged,
        )
    }

    @Test
    fun plannedRangeProtectionIncludesConfiguredNeighborWindow() {
        val protectedPages = plannedRangeProtectionPages(
            listOf(plannedRange(start = 0L, endInclusive = 9L, pages = listOf(10, 12), priority = 0)),
        )

        assertEquals((6..16).toSet(), protectedPages)
    }

    @Test
    fun budgetSelectionKeepsPriorityOrderAndReportsSkippedBytes() {
        val high = plannedRange(0L, 31L, pages = listOf(0), priority = 0)
        val medium = plannedRange(32L, 51L, pages = listOf(1), priority = 1)
        val low = plannedRange(52L, 67L, pages = listOf(2), priority = 3)

        val result = limitPlannedRangesByBudget(
            ranges = listOf(low, medium, high),
            maxBytes = 48L,
        )

        assertEquals(listOf(high, low), result.ranges)
        assertEquals(1, result.skippedCount)
        assertEquals(20L, result.skippedBytes)
    }

    @Test
    fun wrappedReaderCancellationIsExpectedButUnrelatedFailureIsNot() {
        val wrapped = IllegalStateException(
            "range callback failed",
            CancellationException("range request cancelled"),
        )

        assertTrue(wrapped.isExpectedReaderCancellation())
        assertFalse(IllegalStateException("network disconnected").isExpectedReaderCancellation())
    }

    private fun plannedRange(
        start: Long,
        endInclusive: Long,
        pages: List<Int>,
        priority: Int,
    ): PlannedRemoteRange = PlannedRemoteRange(
        start = start,
        endInclusive = endInclusive,
        pages = pages,
        priority = priority,
    )
}
