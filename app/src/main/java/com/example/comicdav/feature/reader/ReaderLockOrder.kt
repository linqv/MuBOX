package com.example.comicdav.feature.reader

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Lock ordering contract for [ReaderViewModel].
 *
 * 1. **sessionMutex** – All native session calls (loadPageToFile, updateViewport,
 *    plannedRanges, prefetchRange, close) MUST be serialized under this coroutine
 *    [Mutex]. Never call native methods outside sessionMutex.
 *
 * 2. **plannedRangeLock** – The [plannedRangeJobs] and [completedPlannedRanges] maps
 *    are only accessed inside `synchronized(plannedRangeLock)`. Do NOT enter a
 *    suspend point while holding this lock (it is a JVM monitor, not a coroutine
 *    primitive).
 *
 * 3. **Semaphore ordering** – Acquire [plannedRangeSemaphore] BEFORE
 *    [lowPriorityPlannedRangeSemaphore]. Reversing this order risks deadlock when
 *    high-priority and low-priority prefetches compete for permits.
 *
 * Violation of any rule above may cause deadlocks or data races.
 */
object ReaderLockOrder

/**
 * Convenience wrapper that acquires [sessionMutex] and runs [block].
 * Callers must not hold [plannedRangeLock] when invoking this.
 */
internal suspend inline fun <T> withSessionMutex(
    mutex: Mutex,
    crossinline block: suspend () -> T,
): T = mutex.withLock { block() }
