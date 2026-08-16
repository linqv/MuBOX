package org.mubox.reader.core.ports

import androidx.annotation.WorkerThread
import java.io.Closeable
import java.io.File

interface ComicReaderSession : Closeable {
    val pageCount: Int
    val forwardPrefetchPageCount: Int
        get() = 4
    val backwardPrefetchPageCount: Int
        get() = 1
    val advancePrefetchOnPageDemand: Boolean
        get() = false

    @WorkerThread
    fun loadPageToFile(pageIndex: Int, outputFile: File): File

    @WorkerThread
    fun updateViewport(pageIndex: Int, networkClass: Int) = Unit

    fun diagnostics(): String = ""

    @WorkerThread
    fun plannedRanges(pageIndex: Int, networkClass: Int): List<PlannedRemoteRange> = emptyList()

    /**
     * Reconciles the viewport's native range plan with work already owned by the caller.
     *
     * Implementations only compute the plan. The caller remains responsible for starting,
     * cancelling, and completing prefetch work.
     */
    @WorkerThread
    fun reconcilePrefetchPlan(
        pageIndex: Int,
        networkClass: Int,
        activeRanges: List<PlannedRemoteRange>,
        completedRanges: List<PlannedRemoteRange>,
        byteBudget: Long,
    ): ReconciledPrefetchPlan = ReconciledPrefetchPlan()

    @WorkerThread
    fun prefetchRange(start: Long, endInclusive: Long): Boolean = false

    @WorkerThread
    fun prefetchRange(
        start: Long,
        endInclusive: Long,
        priority: Int,
        protectedRanges: List<LongRange>,
    ): Boolean = prefetchRange(start, endInclusive)

    fun cancelPrefetches() = Unit
}

data class PlannedRemoteRange(
    val start: Long,
    val endInclusive: Long,
    val pages: List<Int>,
    val priority: Int,
)

data class ReconciledPrefetchTask(
    val range: PlannedRemoteRange,
    val protectedRanges: List<LongRange>,
)

data class ReconciledPrefetchPlan(
    val tasks: List<ReconciledPrefetchTask> = emptyList(),
    val retainedPages: Set<Int> = emptySet(),
)
