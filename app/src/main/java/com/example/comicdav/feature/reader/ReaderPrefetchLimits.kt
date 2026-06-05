package com.example.comicdav.feature.reader

import com.example.comicdav.nativebridge.ComicReaderSession
import com.example.comicdav.nativebridge.PlannedRemoteRange
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/** Maximum concurrent planned-range prefetch operations (all priorities). */
internal const val MAX_PLANNED_RANGE_CONCURRENCY = 2

/** Maximum concurrent low-priority planned-range prefetch operations. */
internal const val MAX_LOW_PRIORITY_PLANNED_RANGE_CONCURRENCY = 1

/** Keep planned byte prefetch under the range cache capacity with room for current reads. */
internal const val PREFETCH_PLAN_MAX_BYTES = 48L * 1024L * 1024L

/** Priority threshold: ranges with priority > this value are low-priority. */
internal const val HIGH_PRIORITY_PLANNED_RANGE_MAX = 2

internal data class PlannedRangeBudgetResult(
    val ranges: List<PlannedRemoteRange>,
    val skippedCount: Int,
    val skippedBytes: Long,
)

internal fun limitPlannedRangesByBudget(
    ranges: List<PlannedRemoteRange>,
    maxBytes: Long = PREFETCH_PLAN_MAX_BYTES,
): PlannedRangeBudgetResult {
    if (maxBytes <= 0L || ranges.isEmpty()) {
        return PlannedRangeBudgetResult(
            ranges = emptyList(),
            skippedCount = ranges.size,
            skippedBytes = ranges.sumOf { it.byteCount() },
        )
    }

    val selected = mutableListOf<PlannedRemoteRange>()
    var selectedBytes = 0L
    var skippedCount = 0
    var skippedBytes = 0L
    ranges.sortedBy { it.priority }.forEach { range ->
        val byteCount = range.byteCount()
        if (selectedBytes + byteCount <= maxBytes) {
            selected += range
            selectedBytes += byteCount
        } else {
            skippedCount++
            skippedBytes += byteCount
        }
    }
    return PlannedRangeBudgetResult(
        ranges = selected,
        skippedCount = skippedCount,
        skippedBytes = skippedBytes,
    )
}

internal fun PlannedRemoteRange.byteCount(): Long =
    endInclusive - start + 1

/**
 * Executes a planned-range prefetch respecting concurrency limits.
 *
 * Acquires [plannedRangeSemaphore] first, then conditionally acquires
 * [lowPriorityPlannedRangeSemaphore] for low-priority ranges.
 * This ordering MUST NOT be reversed (see [ReaderLockOrder]).
 */
internal suspend fun prefetchPlannedRangeWithLimits(
    session: ComicReaderSession,
    range: PlannedRemoteRange,
    protectedRanges: List<LongRange>,
    plannedRangeSemaphore: Semaphore,
    lowPriorityPlannedRangeSemaphore: Semaphore,
): Boolean =
    plannedRangeSemaphore.withPermit {
        if (range.priority > HIGH_PRIORITY_PLANNED_RANGE_MAX) {
            lowPriorityPlannedRangeSemaphore.withPermit {
                session.prefetchRange(range.start, range.endInclusive, range.priority, protectedRanges)
            }
        } else {
            session.prefetchRange(range.start, range.endInclusive, range.priority, protectedRanges)
        }
    }
