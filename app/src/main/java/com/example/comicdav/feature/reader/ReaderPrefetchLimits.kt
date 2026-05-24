package com.example.comicdav.feature.reader

import com.example.comicdav.nativebridge.ComicReaderSession
import com.example.comicdav.nativebridge.PlannedRemoteRange
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/** Maximum concurrent planned-range prefetch operations (all priorities). */
internal const val MAX_PLANNED_RANGE_CONCURRENCY = 2

/** Maximum concurrent low-priority planned-range prefetch operations. */
internal const val MAX_LOW_PRIORITY_PLANNED_RANGE_CONCURRENCY = 1

/** Priority threshold: ranges with priority > this value are low-priority. */
internal const val HIGH_PRIORITY_PLANNED_RANGE_MAX = 2

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
