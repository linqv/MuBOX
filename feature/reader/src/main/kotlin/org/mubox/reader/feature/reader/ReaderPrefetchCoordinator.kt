package org.mubox.reader.feature.reader

import org.mubox.reader.core.diagnostics.Diagnostics
import org.mubox.reader.core.diagnostics.NoopDiagnostics
import org.mubox.reader.core.ports.ComicReaderSession
import org.mubox.reader.core.ports.PlannedRemoteRange
import java.io.File
import kotlin.math.abs
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
) {
    private val lock = Any()
    private val jobs = mutableMapOf<PlannedRangeKey, PlannedRangePrefetch>()
    private val completedRanges = mutableMapOf<PlannedRangeKey, CompletedPlannedRange>()
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
                    val plannedRanges = withContext(ioDispatcher) {
                        sessionCoordinator.withSessionLock {
                            if (!sessionCoordinator.isCurrent(expectedGeneration)) {
                                return@withSessionLock emptyList<PlannedRemoteRange>()
                            }
                            session.updateViewport(pageIndex, NETWORK_WIFI)
                            val ranges = session.plannedRanges(pageIndex, NETWORK_WIFI)
                            ranges
                        }
                    }
                    if (sessionCoordinator.isCurrent(expectedGeneration)) {
                        schedule(session, plannedRanges, expectedGeneration)
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
                val ranges = sessionCoordinator.withSessionLock {
                    if (!sessionCoordinator.isCurrent(expectedGeneration)) {
                        return@withSessionLock emptyList<PlannedRemoteRange>()
                    }
                    activeSession.plannedRanges(pageIndex, NETWORK_WIFI)
                }
                if (sessionCoordinator.isCurrent(expectedGeneration)) {
                    schedule(activeSession, ranges, expectedGeneration)
                }
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

    private fun schedule(
        session: ComicReaderSession,
        ranges: List<PlannedRemoteRange>,
        expectedGeneration: Int,
    ) {
        val mergedRanges = mergeSameStartPlannedRanges(ranges)
        // Nearby active ranges remain useful while continuous scrolling advances the viewport.
        val retainedPages = plannedRangeProtectionPages(mergedRanges)
        cancel(reason = "stale_plan", keepPages = retainedPages)
        if (mergedRanges.isEmpty()) return

        val budgetedPrefetch = limitPlannedRangesByBudget(
            mergedRanges.flatMap(::missingSegments),
        )
        val prefetchRanges = budgetedPrefetch.ranges
        if (prefetchRanges.isEmpty()) return

        prefetchRanges.forEach { range ->
            val key = range.key()
            val protectedRanges = protectedByteRanges(prefetchRanges, excludedKey = key)
            val job = rangeScope.launch(start = CoroutineStart.LAZY) {
                try {
                    if (!sessionCoordinator.isCurrent(expectedGeneration)) return@launch
                    prefetchWithLimits(session, range, protectedRanges)
                    if (sessionCoordinator.isCurrent(expectedGeneration)) {
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
                            }
                        }
                    }
                }
            }
            val shouldStart = synchronized(lock) {
                when {
                    jobs[key]?.job?.isPendingOrActive() == true -> false
                    completedRanges[key] != null -> false
                    else -> {
                        jobs[key] = PlannedRangePrefetch(range = range, job = job)
                        true
                    }
                }
            }
            if (!shouldStart) {
                job.cancel()
                return@forEach
            }
            job.start()
        }
    }

    private fun missingSegments(range: PlannedRemoteRange): List<PlannedRemoteRange> {
        val coveredRanges = synchronized(lock) {
            val activeRanges = jobs
                .values
                .filter { it.job.isPendingOrActive() }
                .map { it.range.key().asLongRange() }
            activeRanges + completedRanges.keys.map(PlannedRangeKey::asLongRange)
        }
        return subtractCoveredRanges(
            start = range.start,
            endInclusive = range.endInclusive,
            coveredRanges = coveredRanges,
        ).map { missing ->
            PlannedRemoteRange(
                start = missing.first,
                endInclusive = missing.last,
                pages = range.pages,
                priority = range.priority,
            )
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

    private fun protectedByteRanges(
        ranges: List<PlannedRemoteRange>,
        excludedKey: PlannedRangeKey,
    ): List<LongRange> {
        val protectionPages = plannedRangeProtectionPages(ranges)
        val currentPages = ranges.flatMap { it.pages }.toSet()
        val candidates = mutableListOf<PlannedRangeProtectionCandidate>()
        ranges.forEach { range ->
            candidates += PlannedRangeProtectionCandidate(
                key = range.key(),
                sourceRank = PLANNED_RANGE_PROTECTION_SOURCE_CURRENT,
                pages = range.pages.toSet(),
                priority = range.priority,
            )
        }
        synchronized(lock) {
            jobs
                .values
                .filter {
                    it.job.isPendingOrActive() &&
                        it.range.pages.any { page -> page in protectionPages }
                }
                .forEach { planned ->
                    candidates += PlannedRangeProtectionCandidate(
                        key = planned.range.key(),
                        sourceRank = PLANNED_RANGE_PROTECTION_SOURCE_ACTIVE,
                        pages = planned.range.pages.toSet(),
                        priority = planned.range.priority,
                    )
                }
            completedRanges
                .filter { (_, completed) ->
                    completed.pages.any { page -> page in protectionPages }
                }
                .forEach { (key, completed) ->
                    candidates += PlannedRangeProtectionCandidate(
                        key = key,
                        sourceRank = PLANNED_RANGE_PROTECTION_SOURCE_COMPLETED,
                        pages = completed.pages,
                        priority = completed.priority,
                    )
                }
        }

        val selected = mutableListOf<LongRange>()
        val seen = mutableSetOf<PlannedRangeKey>()
        var selectedBytes = 0L
        val sortedCandidates = candidates.sortedWith(
            compareBy<PlannedRangeProtectionCandidate> { it.sourceRank }
                .thenBy { pageDistance(it.pages, currentPages) }
                .thenBy { it.priority }
                .thenBy { it.byteLength },
        )
        for (candidate in sortedCandidates) {
            if (candidate.key == excludedKey) continue
            if (!seen.add(candidate.key)) continue
            val byteLength = candidate.byteLength
            if (selectedBytes + byteLength > MAX_PLANNED_RANGE_PROTECTED_BYTES) continue
            selected += candidate.key.asLongRange()
            selectedBytes += byteLength
        }
        return selected
    }

    private fun markCompleted(range: PlannedRemoteRange) {
        synchronized(lock) {
            completedRanges[range.key()] = CompletedPlannedRange(
                priority = range.priority,
                pages = range.pages.toSet(),
            )
        }
    }

    private fun cancel(reason: String, keepPages: Set<Int>) {
        val cancelled = synchronized(lock) {
            jobs
                .filter { (_, planned) ->
                    planned.range.pages.none { page -> page in keepPages }
                }
                .map { (key, _) -> key }
                .toList()
        }
        if (cancelled.isEmpty()) return
        cancelled.forEach { key ->
            synchronized(lock) {
                jobs.remove(key)
            }?.job?.cancel(CancellationException("planned range $reason"))
        }
    }

    private fun resetScope() {
        supervisor.cancel(CancellationException("reader session changed"))
        supervisor = SupervisorJob()
        rangeScope = CoroutineScope(supervisor + ioDispatcher)
        synchronized(lock) {
            jobs.clear()
            completedRanges.clear()
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

internal fun subtractCoveredRanges(
    start: Long,
    endInclusive: Long,
    coveredRanges: List<LongRange>,
): List<LongRange> {
    if (endInclusive < start) return emptyList()
    val clipped = coveredRanges
        .mapNotNull { covered ->
            val clippedStart = maxOf(start, covered.first)
            val clippedEnd = minOf(endInclusive, covered.last)
            if (clippedStart <= clippedEnd) clippedStart..clippedEnd else null
        }
        .sortedBy { it.first }
    if (clipped.isEmpty()) return listOf(start..endInclusive)

    val missing = mutableListOf<LongRange>()
    var cursor = start
    clipped.forEach { covered ->
        if (covered.last < cursor) return@forEach
        if (covered.first > endInclusive) return@forEach
        if (covered.first > cursor) {
            missing += cursor..(covered.first - 1)
        }
        if (covered.last == Long.MAX_VALUE) return missing
        cursor = maxOf(cursor, covered.last + 1)
    }
    if (cursor <= endInclusive) {
        missing += cursor..endInclusive
    }
    return missing
}

internal fun mergeSameStartPlannedRanges(ranges: List<PlannedRemoteRange>): List<PlannedRemoteRange> =
    ranges
        .groupBy { it.start }
        .map { (start, group) ->
            PlannedRemoteRange(
                start = start,
                endInclusive = group.maxOf { it.endInclusive },
                pages = group.flatMap { it.pages }.distinct().sorted(),
                priority = group.minOf { it.priority },
            )
        }

internal fun plannedRangeProtectionPages(ranges: List<PlannedRemoteRange>): Set<Int> {
    val pages = ranges.flatMap { it.pages }
    if (pages.isEmpty()) return emptySet()
    val firstPage = (pages.min() - ReaderPrefetchPlanner.FORWARD_PAGES).coerceAtLeast(0)
    val lastPage = pages.max() + ReaderPrefetchPlanner.FORWARD_PAGES
    return (firstPage..lastPage).toSet()
}

internal fun Throwable.isExpectedReaderCancellation(): Boolean {
    if (this is CancellationException) return true
    cause?.let { cause ->
        if (cause.isExpectedReaderCancellation()) return true
    }
    val text = message ?: return false
    return text.contains("CancellationException") &&
        EXPECTED_READER_CANCELLATION_MESSAGES.any { text.contains(it) }
}

private fun pageDistance(candidatePages: Set<Int>, currentPages: Set<Int>): Int {
    if (candidatePages.isEmpty() || currentPages.isEmpty()) return Int.MAX_VALUE
    return candidatePages.minOf { candidate ->
        currentPages.minOf { current -> abs(candidate - current) }
    }
}

private fun Job.isPendingOrActive(): Boolean = !isCompleted && !isCancelled

private data class PlannedRangeKey(
    val start: Long,
    val endInclusive: Long,
) {
    fun asLongRange(): LongRange = start..endInclusive
}

private data class PlannedRangePrefetch(
    val range: PlannedRemoteRange,
    val job: Job,
)

private data class CompletedPlannedRange(
    val priority: Int,
    val pages: Set<Int>,
)

private data class PlannedRangeProtectionCandidate(
    val key: PlannedRangeKey,
    val sourceRank: Int,
    val pages: Set<Int>,
    val priority: Int,
) {
    val byteLength: Long
        get() = key.endInclusive - key.start + 1
}

private fun PlannedRemoteRange.key(): PlannedRangeKey =
    PlannedRangeKey(start = start, endInclusive = endInclusive)

private const val PREFETCH_START_DELAY_MS = 150L
private const val PREFETCH_STAGGER_MS = 1L
private const val NETWORK_WIFI = 2
private const val MAX_PLANNED_RANGE_PROTECTED_BYTES = 32L * 1024L * 1024L
private const val CONTINUOUS_PAGE_PREFETCH_RETENTION_BEHIND = 2
private const val CONTINUOUS_PAGE_PREFETCH_RETENTION_AHEAD = 2
private const val PLANNED_RANGE_PROTECTION_SOURCE_CURRENT = 0
private const val PLANNED_RANGE_PROTECTION_SOURCE_ACTIVE = 1
private const val PLANNED_RANGE_PROTECTION_SOURCE_COMPLETED = 2
private const val LOAD_REASON_PREFETCH = "prefetch"
private val EXPECTED_READER_CANCELLATION_MESSAGES = listOf(
    "range fetch cancelled",
    "range prefetch cancelled",
    "range request cancelled",
    "range provider closed",
    "reader session changed",
)
