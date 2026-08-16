package org.mubox.reader.feature.reader

import org.mubox.reader.core.diagnostics.Diagnostics
import org.mubox.reader.core.diagnostics.NoopDiagnostics
import org.mubox.reader.core.ports.ComicReaderSession
import org.mubox.reader.core.ports.PlannedRemoteRange
import org.mubox.reader.core.ports.ReconciledPrefetchPlan
import org.mubox.reader.core.ports.ReconciledPrefetchTask
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield

/**
 * Owns all speculative reader work while the ViewModel remains responsible for UI events.
 *
 * Page extraction prefetch and remote byte-range prefetch have separate state machines, but
 * share the active reader generation so stale work can never publish into a newer session.
 */
internal class ReaderPrefetchCoordinator(
    scope: CoroutineScope,
    ioDispatcher: CoroutineDispatcher,
    sessionCoordinator: ReaderSessionCoordinator,
    pageLoadCoordinator: ReaderPageLoadCoordinator,
    diagnosticLog: Diagnostics = NoopDiagnostics,
    pageFiles: () -> Map<Int, File>,
    onPageFilesLoaded: (Map<Int, File>) -> Unit,
    networkClass: () -> Int = { NETWORK_CLASS_WIFI },
) {
    private val pagePrefetch = ReaderPagePrefetchCoordinator(
        scope = scope,
        sessionCoordinator = sessionCoordinator,
        pageLoadCoordinator = pageLoadCoordinator,
        diagnosticLog = diagnosticLog,
        pageFiles = pageFiles,
        onPageFilesLoaded = onPageFilesLoaded,
    )
    private val plannedRangePrefetch = ReaderPlannedRangePrefetchCoordinator(
        scope = scope,
        ioDispatcher = ioDispatcher,
        sessionCoordinator = sessionCoordinator,
        diagnosticLog = diagnosticLog,
        networkClass = networkClass,
    )

    fun prefetchNeighbors(pageIndex: Int, reason: String = "viewport") {
        pagePrefetch.prefetchNeighbors(pageIndex, reason)
    }

    fun prioritizeSelectedPageLoad(pageIndex: Int) {
        pagePrefetch.prioritizeSelectedPageLoad(pageIndex)
    }

    fun updateViewport(
        session: ComicReaderSession,
        pageIndex: Int,
        expectedGeneration: Int,
    ) {
        plannedRangePrefetch.updateViewport(session, pageIndex, expectedGeneration)
    }

    fun prefetchDemandRanges(pageIndex: Int, source: String) {
        plannedRangePrefetch.prefetchDemandRanges(pageIndex, source)
    }

    fun cancelSessionWork(selectedPage: Int) {
        pagePrefetch.cancelAll(selectedPage)
        plannedRangePrefetch.cancelForSessionChange()
    }

    fun shutdown() {
        plannedRangePrefetch.shutdown()
    }
}

private class ReaderPagePrefetchCoordinator(
    private val scope: CoroutineScope,
    private val sessionCoordinator: ReaderSessionCoordinator,
    private val pageLoadCoordinator: ReaderPageLoadCoordinator,
    private val diagnosticLog: Diagnostics,
    private val pageFiles: () -> Map<Int, File>,
    private val onPageFilesLoaded: (Map<Int, File>) -> Unit,
) {
    private val jobs = mutableMapOf<Int, Job>()

    fun prefetchNeighbors(pageIndex: Int, reason: String) {
        val activeReader = sessionCoordinator.activeSession ?: return
        val activeSession = activeReader.session
        val opening = activeReader.descriptor
        val forwardPrefetchPages = activeSession.forwardPrefetchPageCount.coerceAtLeast(0)
        val backwardPrefetchPages = activeSession.backwardPrefetchPageCount.coerceAtLeast(0)
        if (forwardPrefetchPages == 0 && backwardPrefetchPages == 0) {
            return
        }
        val desiredWindow = ReaderPrefetchPlanner.desiredPageWindow(
            pageIndex = pageIndex,
            pageCount = activeSession.pageCount,
            forwardPages = forwardPrefetchPages,
            backwardPages = backwardPrefetchPages,
        )
        val retentionWindow = retainedPagePrefetchWindow(
            pageIndex = pageIndex,
            pageCount = activeSession.pageCount,
            forwardPages = forwardPrefetchPages,
            desiredWindow = desiredWindow,
            reason = reason,
        )
        reconcile(
            selectedPage = pageIndex,
            retentionWindow = retentionWindow,
            reason = reason,
        )
        val missingNeighbors = ReaderPrefetchPlanner.neighborPrefetchPages(
            pageIndex = pageIndex,
            pageCount = activeSession.pageCount,
            forwardPages = forwardPrefetchPages,
            backwardPages = backwardPrefetchPages,
        )
            .filterNot { pageFiles().containsKey(it) }
            .filterNot { jobs[it]?.isActive == true }
        if (missingNeighbors.isEmpty()) return

        val prefetchGeneration = opening.generation
        missingNeighbors.forEachIndexed { order, page ->
            val job = scope.launch {
                try {
                    delay(PREFETCH_START_DELAY_MS + order * PREFETCH_STAGGER_MS)
                    currentCoroutineContext().ensureActive()
                    val files = pageLoadCoordinator.loadPages(
                        session = activeSession,
                        context = opening.pageLoadContext(),
                        pageIndexes = listOf(page),
                        reason = LOAD_REASON_PREFETCH,
                    )
                    currentCoroutineContext().ensureActive()
                    if (sessionCoordinator.isCurrent(prefetchGeneration)) {
                        onPageFilesLoaded(files)
                    }
                } catch (_: CancellationException) {
                    // Expected lifecycle cancellation is logged by the reconciler.
                } catch (error: Throwable) {
                    if (error.isExpectedReaderCancellation()) return@launch
                    cancel(
                        reason = "dependency_failed",
                        pages = missingNeighbors.drop(order + 1),
                        selectedPage = pageIndex,
                    )
                    diagnosticLog.error("prefetch_failed page=$page", error)
                } finally {
                    val currentJob = currentCoroutineContext()[Job]
                    if (currentJob != null && jobs[page] === currentJob) {
                        jobs.remove(page)
                    }
                }
            }
            jobs[page] = job
        }
    }

    fun prioritizeSelectedPageLoad(pageIndex: Int) {
        val activePages = jobs.keys.toList()
        if (activePages.isEmpty()) return
        cancel(
            reason = "selected_page_priority",
            pages = activePages,
            selectedPage = pageIndex,
        )
    }

    fun cancelAll(selectedPage: Int) {
        cancel(
            reason = "stale_generation",
            pages = jobs.keys.toList(),
            selectedPage = selectedPage,
        )
    }

    private fun reconcile(
        selectedPage: Int,
        retentionWindow: Set<Int>,
        reason: String,
    ) {
        val activePages = jobs
            .filter { (_, job) -> job.isActive }
            .keys
            .toSet()
        val cancelledPages = activePages.subtract(retentionWindow)
        cancel(
            reason = "outside_window",
            pages = cancelledPages.toList(),
            selectedPage = selectedPage,
        )
    }

    private fun cancel(reason: String, pages: List<Int>, selectedPage: Int) {
        val activePages = pages
            .distinct()
            .filter { jobs[it] != null }
        if (activePages.isEmpty()) return
        activePages.forEach { page ->
            jobs.remove(page)?.cancel(CancellationException("prefetch $reason"))
        }
    }
}

private class ReaderPlannedRangePrefetchCoordinator(
    private val scope: CoroutineScope,
    private val ioDispatcher: CoroutineDispatcher,
    private val sessionCoordinator: ReaderSessionCoordinator,
    private val diagnosticLog: Diagnostics,
    private val networkClass: () -> Int,
) {
    private val lock = Any()
    private val jobs = mutableMapOf<PlannedRangeKey, PlannedRangePrefetch>()
    private val completedRanges = mutableMapOf<PlannedRangeKey, CompletedPlannedRange>()
    private var rangeStateRevision = 0L
    private var supervisor = SupervisorJob()
    private var rangeScope = CoroutineScope(supervisor + ioDispatcher)
    private val rangeSemaphore = Semaphore(MAX_PLANNED_RANGE_CONCURRENCY)
    private val lowPriorityRangeSemaphore = Semaphore(MAX_LOW_PRIORITY_PLANNED_RANGE_CONCURRENCY)

    fun updateViewport(
        session: ComicReaderSession,
        pageIndex: Int,
        expectedGeneration: Int,
    ) {
        sessionCoordinator.replaceViewportJob {
            scope.launch {
                runCatching {
                    withContext(ioDispatcher) {
                        reconcileUntilScheduled(
                            session = session,
                            pageIndex = pageIndex,
                            expectedGeneration = expectedGeneration,
                            updateViewport = true,
                        )
                    }
                }.onFailure { error ->
                    if (sessionCoordinator.isCurrent(expectedGeneration) && error !is CancellationException) {
                        diagnosticLog.error("update_viewport_failed page=$pageIndex", error)
                    }
                }
            }
        }
    }

    fun prefetchDemandRanges(pageIndex: Int, source: String) {
        val activeReader = sessionCoordinator.activeSession ?: return
        val activeSession = activeReader.session
        val expectedGeneration = activeReader.descriptor.generation
        rangeScope.launch {
            runCatching {
                reconcileUntilScheduled(
                    session = activeSession,
                    pageIndex = pageIndex,
                    expectedGeneration = expectedGeneration,
                    updateViewport = false,
                )
            }.onFailure { error ->
                if (sessionCoordinator.isCurrent(expectedGeneration) && error !is CancellationException) {
                    diagnosticLog.error("demand_planned_range_failed page=$pageIndex source=$source", error)
                }
            }
        }
    }

    fun cancelForSessionChange() {
        cancel(reason = "stale_generation", keepPages = emptySet())
        resetScope()
    }

    fun shutdown() {
        supervisor.cancel(CancellationException("reader view model cleared"))
    }

    private suspend fun reconcileUntilScheduled(
        session: ComicReaderSession,
        pageIndex: Int,
        expectedGeneration: Int,
        updateViewport: Boolean,
    ) {
        var viewportUpdatePending = updateViewport
        while (sessionCoordinator.isCurrent(expectedGeneration)) {
            val activeNetworkClass = networkClass()
            val snapshot = sessionCoordinator.withSessionLock {
                if (!sessionCoordinator.isCurrent(expectedGeneration)) {
                    return@withSessionLock null
                }
                if (viewportUpdatePending) {
                    session.updateViewport(pageIndex, activeNetworkClass)
                    viewportUpdatePending = false
                }
                val state = snapshotRangeState()
                val plan = session.reconcilePrefetchPlan(
                    pageIndex = pageIndex,
                    networkClass = activeNetworkClass,
                    activeRanges = state.activeRanges,
                    completedRanges = state.completedRanges,
                    byteBudget = PREFETCH_PLAN_MAX_BYTES,
                )
                ReconciledPrefetchPlanSnapshot(
                    plan = plan,
                    rangeStateRevision = state.revision,
                )
            } ?: return
            currentCoroutineContext().ensureActive()
            if (!sessionCoordinator.isCurrent(expectedGeneration)) return
            if (
                schedule(
                    session = session,
                    plan = snapshot.plan,
                    expectedGeneration = expectedGeneration,
                    expectedRangeStateRevision = snapshot.rangeStateRevision,
                )
            ) {
                return
            }
            // A job completed or was cancelled while native reconciliation was running.
            // Retry from a fresh atomic snapshot rather than applying stale coverage.
            yield()
        }
    }

    private fun schedule(
        session: ComicReaderSession,
        plan: ReconciledPrefetchPlan,
        expectedGeneration: Int,
        expectedRangeStateRevision: Long,
    ): Boolean {
        val jobsToCancel = mutableListOf<Job>()
        val jobsToStart = mutableListOf<Job>()
        val applied = synchronized(lock) {
            if (rangeStateRevision != expectedRangeStateRevision) {
                return@synchronized false
            }
            var stateChanged = false
            // Nearby active ranges remain useful while continuous scrolling advances the viewport.
            val staleKeys = jobs
                .filter { (_, planned) ->
                    planned.range.pages.none { page -> page in plan.retainedPages }
                }
                .keys
                .toList()
            staleKeys.forEach { key ->
                jobs.remove(key)?.job?.let(jobsToCancel::add)
                stateChanged = true
            }
            plan.tasks.forEach { task ->
                val range = task.range
                val key = range.key()
                if (
                    jobs[key]?.job?.isPendingOrActive() == true ||
                    completedRanges[key] != null
                ) {
                    return@forEach
                }
                val job = createPrefetchJob(
                    session = session,
                    task = task,
                    expectedGeneration = expectedGeneration,
                )
                jobs[key] = PlannedRangePrefetch(range = range, job = job)
                jobsToStart += job
                stateChanged = true
            }
            if (stateChanged) {
                rangeStateRevision++
            }
            true
        }
        if (!applied) return false
        jobsToCancel.forEach { job ->
            job.cancel(CancellationException("planned range stale_plan"))
        }
        jobsToStart.forEach(Job::start)
        return true
    }

    private fun createPrefetchJob(
        session: ComicReaderSession,
        task: ReconciledPrefetchTask,
        expectedGeneration: Int,
    ): Job {
        val range = task.range
        val key = range.key()
        return rangeScope.launch(start = CoroutineStart.LAZY) {
            try {
                if (!sessionCoordinator.isCurrent(expectedGeneration)) return@launch
                val completed = prefetchWithLimits(session, range, task.protectedRanges)
                // A rejected, cancelled or not-stored prefetch must NOT be recorded as
                // completed: the next reconcile then re-schedules the same range instead
                // of treating it as covered for the rest of the session.
                if (completed && sessionCoordinator.isCurrent(expectedGeneration)) {
                    markCompleted(range)
                }
            } catch (_: CancellationException) {
                // Expected stale-plan cancellation is logged by the reconciler.
            } catch (error: Throwable) {
                if (error.isExpectedReaderCancellation()) return@launch
                if (sessionCoordinator.isCurrent(expectedGeneration)) {
                    diagnosticLog.error(
                        "planned_range_prefetch_failed start=${range.start} end=${range.endInclusive}",
                        error,
                    )
                }
            } finally {
                val currentJob = currentCoroutineContext()[Job]
                if (currentJob != null) {
                    synchronized(lock) {
                        if (jobs[key]?.job === currentJob) {
                            jobs.remove(key)
                            rangeStateRevision++
                        }
                    }
                }
            }
        }
    }

    private suspend fun prefetchWithLimits(
        session: ComicReaderSession,
        range: PlannedRemoteRange,
        protectedRanges: List<LongRange>,
    ): Boolean = prefetchPlannedRangeWithLimits(
        session = session,
        range = range,
        protectedRanges = protectedRanges,
        plannedRangeSemaphore = rangeSemaphore,
        lowPriorityPlannedRangeSemaphore = lowPriorityRangeSemaphore,
    )

    private fun snapshotRangeState(): PlannedRangeStateSnapshot = synchronized(lock) {
        val activeRanges = jobs
            .values
            .filter { it.job.isPendingOrActive() }
            .map { it.range }
        val completed = completedRanges.map { (key, range) ->
            PlannedRemoteRange(
                start = key.start,
                endInclusive = key.endInclusive,
                pages = range.pages.sorted(),
                priority = range.priority,
            )
        }
        PlannedRangeStateSnapshot(
            activeRanges = activeRanges,
            completedRanges = completed,
            revision = rangeStateRevision,
        )
    }

    private fun markCompleted(range: PlannedRemoteRange) {
        synchronized(lock) {
            val completed = CompletedPlannedRange(
                priority = range.priority,
                pages = range.pages.toSet(),
            )
            if (completedRanges.put(range.key(), completed) != completed) {
                rangeStateRevision++
            }
        }
    }

    private fun cancel(reason: String, keepPages: Set<Int>) {
        val cancelled = synchronized(lock) {
            val keys = jobs
                .filter { (_, planned) ->
                    planned.range.pages.none { page -> page in keepPages }
                }
                .map { (key, _) -> key }
                .toList()
            val removed = keys.mapNotNull(jobs::remove)
            if (removed.isNotEmpty()) {
                rangeStateRevision++
            }
            removed
        }
        if (cancelled.isEmpty()) return
        cancelled.forEach { planned ->
            planned.job.cancel(CancellationException("planned range $reason"))
        }
    }

    private fun resetScope() {
        supervisor.cancel(CancellationException("reader session changed"))
        supervisor = SupervisorJob()
        rangeScope = CoroutineScope(supervisor + ioDispatcher)
        synchronized(lock) {
            jobs.clear()
            completedRanges.clear()
            rangeStateRevision++
        }
    }
}

internal fun retainedPagePrefetchWindow(
    pageIndex: Int,
    pageCount: Int,
    forwardPages: Int,
    desiredWindow: Set<Int>,
    reason: String,
): Set<Int> {
    if (reason != "continuous_visible") return desiredWindow
    val firstPage = (pageIndex - CONTINUOUS_PAGE_PREFETCH_RETENTION_BEHIND).coerceAtLeast(0)
    val lastPage = (pageIndex + forwardPages + CONTINUOUS_PAGE_PREFETCH_RETENTION_AHEAD)
        .coerceAtMost(pageCount - 1)
    if (lastPage < firstPage) return desiredWindow
    return desiredWindow + (firstPage..lastPage)
}

internal fun Throwable.isExpectedReaderCancellation(): Boolean {
    if (this is CancellationException) return true
    cause?.let { cause ->
        if (cause.isExpectedReaderCancellation()) return true
    }
    // Native sessions surface cancellation as a plain ComicNativeException whose
    // message is the Rust error text, so classification falls back to the exact
    // messages the transport and range-session layers produce.
    val text = message ?: return false
    return EXPECTED_READER_CANCELLATION_MESSAGES.any { text.contains(it) }
}

private fun Job.isPendingOrActive(): Boolean = !isCompleted && !isCancelled

private data class PlannedRangeKey(
    val start: Long,
    val endInclusive: Long,
)

private data class PlannedRangePrefetch(
    val range: PlannedRemoteRange,
    val job: Job,
)

private data class CompletedPlannedRange(
    val priority: Int,
    val pages: Set<Int>,
)

private data class PlannedRangeStateSnapshot(
    val activeRanges: List<PlannedRemoteRange>,
    val completedRanges: List<PlannedRemoteRange>,
    val revision: Long,
)

private data class ReconciledPrefetchPlanSnapshot(
    val plan: ReconciledPrefetchPlan,
    val rangeStateRevision: Long,
)

private fun PlannedRemoteRange.key(): PlannedRangeKey =
    PlannedRangeKey(start = start, endInclusive = endInclusive)

private const val PREFETCH_START_DELAY_MS = 150L
private const val PREFETCH_STAGGER_MS = 1L
private const val CONTINUOUS_PAGE_PREFETCH_RETENTION_BEHIND = 2
private const val CONTINUOUS_PAGE_PREFETCH_RETENTION_AHEAD = 2
private const val LOAD_REASON_PREFETCH = "prefetch"
private val EXPECTED_READER_CANCELLATION_MESSAGES = listOf(
    "range request cancelled",
    "remote range session closed",
    "range provider closed",
    "reader session changed",
)
