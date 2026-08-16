package org.mubox.reader.feature.reader

import org.mubox.reader.CollectingReaderLogSink
import org.mubox.reader.MainDispatcherRule
import org.mubox.reader.core.diagnostics.ExceptionDiagnostics
import org.mubox.reader.core.diagnostics.Diagnostics
import org.mubox.reader.core.diagnostics.NoopDiagnostics
import org.mubox.reader.core.model.history.WatchHistoryEntry
import org.mubox.reader.core.model.history.WatchHistoryMetadata
import org.mubox.reader.core.model.history.WatchMediaType
import org.mubox.reader.core.model.history.WatchSourceType
import org.mubox.reader.core.ports.ComicReaderSession
import org.mubox.reader.core.ports.PlannedRemoteRange
import org.mubox.reader.core.ports.ReconciledPrefetchPlan
import org.mubox.reader.core.ports.ReconciledPrefetchTask
import java.io.File
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

@OptIn(ExperimentalCoroutinesApi::class)
class ReaderViewModelTest {
    @get:Rule
    val temp = TemporaryFolder()

    @get:Rule
    val mainDispatcher = MainDispatcherRule()

    private fun kotlinx.coroutines.test.TestScope.createTestViewModel(
        diagnosticLog: Diagnostics = NoopDiagnostics,
        networkClassProvider: () -> Int = { NETWORK_CLASS_WIFI },
    ): ReaderViewModel {
        val dispatcher = StandardTestDispatcher(testScheduler)
        mainDispatcher.set(dispatcher)
        return ReaderViewModel(
            ioDispatcher = dispatcher,
            diagnosticLog = diagnosticLog,
            networkClassProvider = networkClassProvider,
        )
    }

    private fun openDefaultSession(viewModel: ReaderViewModel, session: ComicReaderSession) {
        viewModel.openExistingSession(
            openedSession = session,
            cacheDir = temp.root,
            initialPage = 0,
            comicKey = "comic",
        )
    }

    @Test
    fun selectPageCacheMissLoadsSelectedPageBeforeQueuedPrefetch() = runTest {
        val session = RecordingComicSession(pageCount = 8, forwardPrefetchPageCount = 4)
        val viewModel = createTestViewModel()

        openDefaultSession(viewModel, session)
        runCurrent()
        assertEquals(listOf(0), session.loadedPages)

        viewModel.selectPage(3)
        runCurrent()

        assertEquals(listOf(0, 3), session.loadedPages.take(2))
    }

    @Test
    fun openSessionExtractPrefetchUsesConfiguredForwardWindow() = runTest {
        val session = RecordingComicSession(pageCount = 20, forwardPrefetchPageCount = 12)
        val viewModel = createTestViewModel()

        openDefaultSession(viewModel, session)
        runCurrent()
        advanceTimeBy(200)
        runCurrent()

        assertEquals((0..12).toList(), session.loadedPages)
    }

    @Test
    fun openSessionConsumesNativeReconciliation() = runTest {
        val range = plannedRange(start = 1024, sizeBytes = 512, priority = 1)
        val session = RecordingComicSession(
            pageCount = 20,
            forwardPrefetchPageCount = 4,
            reconciledPlansForPage = {
                ReconciledPrefetchPlan(
                    retainedPages = setOf(0, 1, 2),
                    tasks = listOf(
                        ReconciledPrefetchTask(
                            range = range,
                            protectedRanges = listOf(0L..511L),
                        ),
                    ),
                )
            },
        )
        val viewModel = createTestViewModel()

        openDefaultSession(viewModel, session)
        runCurrent()

        assertEquals(listOf(0), session.reconciledPlanPages)
        assertEquals(listOf(range.key()), session.prefetchedRanges)
        assertEquals(listOf(listOf(0L..511L)), session.prefetchProtectedRanges)
    }

    @Test
    fun replacedViewportDoesNotSchedulePlanReturnedAfterCancellation() = runTest {
        val staleRange = plannedRange(start = 1_024, sizeBytes = 512, priority = 0)
        val currentRange = plannedRange(start = 2_048, sizeBytes = 512, priority = 0)
        lateinit var viewModel: ReaderViewModel
        var reconciliationCalls = 0
        val session = RecordingComicSession(
            pageCount = 20,
            forwardPrefetchPageCount = 4,
            reconciledPlansForPage = {
                reconciliationCalls++
                val range = if (reconciliationCalls == 1) {
                    // Replaces and cancels the viewport job while its synchronous native call is active.
                    viewModel.selectPage(0)
                    staleRange
                } else {
                    currentRange
                }
                ReconciledPrefetchPlan(
                    retainedPages = setOf(0),
                    tasks = listOf(
                        ReconciledPrefetchTask(
                            range = range,
                            protectedRanges = emptyList(),
                        ),
                    ),
                )
            },
        )
        viewModel = createTestViewModel()

        openDefaultSession(viewModel, session)
        runCurrent()

        assertEquals(2, reconciliationCalls)
        assertEquals(listOf(currentRange.key()), session.prefetchedRanges)
    }

    @Test
    fun rejectedPrefetchIsRetriedAndCompletedOnlyOnSuccess() = runTest {
        val range = plannedRange(start = 1024, sizeBytes = 512, priority = 1)
        var prefetchAttempts = 0
        val session = RecordingComicSession(
            pageCount = 20,
            forwardPrefetchPageCount = 4,
            reconciledPlansForPage = {
                ReconciledPrefetchPlan(
                    retainedPages = setOf(0),
                    tasks = listOf(
                        ReconciledPrefetchTask(
                            range = range,
                            protectedRanges = emptyList(),
                        ),
                    ),
                )
            },
            prefetchResult = { _, _ -> prefetchAttempts++ == 1 },
        )
        val viewModel = createTestViewModel()

        openDefaultSession(viewModel, session)
        runCurrent()

        // First attempt is rejected: the range must NOT be recorded as completed.
        assertEquals(listOf(range.key()), session.prefetchedRanges)
        assertEquals(1, prefetchAttempts)

        // The next demand re-schedules the rejected range; the second attempt succeeds.
        viewModel.reportPageDemand(1, "pager_target")
        runCurrent()
        assertEquals(listOf(range.key(), range.key()), session.prefetchedRanges)
        assertEquals(2, prefetchAttempts)

        // Once completed, further demands must not re-schedule the same range.
        viewModel.reportPageDemand(2, "pager_target")
        runCurrent()
        assertEquals(listOf(range.key(), range.key()), session.prefetchedRanges)
        assertEquals(2, prefetchAttempts)
    }

    @Test
    fun failedPrefetchIsNotMarkedCompletedAndIsRetried() = runTest {
        val range = plannedRange(start = 2048, sizeBytes = 512, priority = 0)
        var prefetchAttempts = 0
        val session = RecordingComicSession(
            pageCount = 20,
            forwardPrefetchPageCount = 4,
            reconciledPlansForPage = {
                ReconciledPrefetchPlan(
                    retainedPages = setOf(0),
                    tasks = listOf(
                        ReconciledPrefetchTask(
                            range = range,
                            protectedRanges = emptyList(),
                        ),
                    ),
                )
            },
            prefetchResult = { _, _ ->
                prefetchAttempts++
                if (prefetchAttempts == 1) throw IllegalStateException("network lost")
                true
            },
        )
        val viewModel = createTestViewModel()

        openDefaultSession(viewModel, session)
        runCurrent()
        assertEquals(listOf(range.key()), session.prefetchedRanges)
        assertEquals(1, prefetchAttempts)

        viewModel.reportPageDemand(1, "pager_target")
        runCurrent()
        assertEquals(listOf(range.key(), range.key()), session.prefetchedRanges)
        assertEquals(2, prefetchAttempts)
    }

    @Test
    fun viewportAndReconcileUseTheProvidedNetworkClass() = runTest {
        val session = RecordingComicSession(pageCount = 20, forwardPrefetchPageCount = 4)
        val viewModel = createTestViewModel(networkClassProvider = { NETWORK_CLASS_MOBILE })

        openDefaultSession(viewModel, session)
        runCurrent()

        assertEquals(listOf(NETWORK_CLASS_MOBILE), session.updateViewportNetworkClasses)
        assertEquals(listOf(NETWORK_CLASS_MOBILE), session.reconciledPlanNetworkClasses)
    }

    @Test
    fun closeReaderCancelsSessionPrefetchesBeforeAsyncClose() = runTest {
        val session = RecordingComicSession(pageCount = 2, forwardPrefetchPageCount = 0)
        val viewModel = createTestViewModel()

        openDefaultSession(viewModel, session)
        runCurrent()

        viewModel.closeReader()

        assertEquals(1, session.cancelPrefetchesCalls)
        assertEquals(0, session.closeCalls)
        runCurrent()
        assertEquals(1, session.closeCalls)
    }

    @Test
    fun pagePrefetchCancellationWrappedByEngineErrorDoesNotLogFailure() = runTest {
        val sink = CollectingReaderLogSink()
        val diagnostics = ExceptionDiagnostics(
            sink = sink,
        )
        val session = RecordingComicSession(
            pageCount = 4,
            forwardPrefetchPageCount = 1,
            pageErrors = mapOf(
                1 to IllegalStateException("remote range session closed"),
            ),
        )
        val viewModel = createTestViewModel(diagnostics)

        openDefaultSession(viewModel, session)
        runCurrent()
        advanceTimeBy(200)
        runCurrent()

        assertFalse(sink.lines.any { it.contains("prefetch_failed") })
    }

    @Test
    fun readyDemandPageRefreshesPlannedRangesWhenSessionAdvancesOnDemand() = runTest {
        val session = RecordingComicSession(
            pageCount = 8,
            forwardPrefetchPageCount = 4,
            advancePrefetchOnPageDemand = true,
        )
        val viewModel = createTestViewModel()

        openDefaultSession(viewModel, session)
        runCurrent()
        advanceTimeBy(200)
        runCurrent()
        session.reconciledPlanPages.clear()

        viewModel.reportPageDemand(1, "pager_target")
        runCurrent()

        assertEquals(listOf(1), session.reconciledPlanPages)
    }

    @Test
    fun readyCurrentPageDemandAddsTrailingPlannedRangeForConfiguredWebDavWindow() = runTest {
        val forwardWindow = 12
        val session = RecordingComicSession(
            pageCount = 20,
            forwardPrefetchPageCount = forwardWindow,
            advancePrefetchOnPageDemand = true,
            reconciledPlansForPage = { page ->
                val ranges = plannedRangesForWindow(
                    pageIndex = page,
                    pageCount = 20,
                    forwardPages = forwardWindow,
                )
                ReconciledPrefetchPlan(
                    retainedPages = ranges.flatMap(PlannedRemoteRange::pages).toSet(),
                    tasks = ranges.map { range ->
                        ReconciledPrefetchTask(range = range, protectedRanges = emptyList())
                    },
                )
            },
        )
        val viewModel = createTestViewModel()

        openDefaultSession(viewModel, session)
        runCurrent()
        advanceTimeBy(200)
        runCurrent()
        assertEquals((0..12).toList(), session.loadedPages)
        assertEquals((0..12).map { plannedRangeForPage(it).key() }, session.prefetchedRanges)
        session.reconciledPlanPages.clear()
        session.prefetchedRanges.clear()

        viewModel.reportPageDemand(1, "pager_current")
        runCurrent()
        advanceTimeBy(200)
        runCurrent()

        assertEquals(listOf(1), session.reconciledPlanPages)
        assertEquals(listOf(plannedRangeForPage(13).key()), session.prefetchedRanges)
        assertEquals((0..13).toList(), session.loadedPages)
    }

    @Test
    fun disabledPageImageCacheBypassesExistingCachedPageFile() = runTest {
        val session = RecordingComicSession(pageCount = 2, forwardPrefetchPageCount = 0)
        val viewModel = createTestViewModel()
        viewModel.updatePageImageCacheEnabled(false)
        val cachedFile = ReaderPageCache.pageFile(temp.root, "comic", 0)
        cachedFile.writeBytes(byteArrayOf(42))

        openDefaultSession(viewModel, session)
        runCurrent()

        assertEquals(listOf(0), session.loadedPages)
        assertNotEquals(cachedFile.absolutePath, viewModel.uiState.pageFiles[0]?.absolutePath)
        assertEquals(listOf(0.toByte()), viewModel.uiState.pageFiles[0]?.readBytes()?.toList())
        assertEquals(listOf(42.toByte()), cachedFile.readBytes().toList())
    }

    @Test
    fun openingAndTurningPagesRecordsOneBasedComicProgress() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        mainDispatcher.set(dispatcher)
        val recorded = mutableListOf<WatchHistoryEntry>()
        val viewModel = ReaderViewModel(
            ioDispatcher = dispatcher,
            recordHistory = recorded::add,
        )
        viewModel.openExistingSession(
            openedSession = RecordingComicSession(pageCount = 12, forwardPrefetchPageCount = 0),
            cacheDir = temp.root,
            initialPage = 2,
            comicKey = "comic-key",
            historyMetadata = WatchHistoryMetadata(
                mediaKey = "comic-key",
                mediaType = WatchMediaType.COMIC,
                title = "Volume 1",
                sourceType = WatchSourceType.LOCAL,
                sourceLocator = "content://volume-1",
            ),
        )
        runCurrent()

        assertEquals(3L, recorded.last().progress)
        assertEquals(12L, recorded.last().total)

        viewModel.selectPage(5)
        runCurrent()

        assertEquals(6L, recorded.last().progress)
    }

}


private class RecordingComicSession(
    override val pageCount: Int,
    override val forwardPrefetchPageCount: Int,
    private val reconciledPlansForPage: (Int) -> ReconciledPrefetchPlan = { ReconciledPrefetchPlan() },
    private val pageErrors: Map<Int, Throwable> = emptyMap(),
    override val advancePrefetchOnPageDemand: Boolean = false,
    private val prefetchResult: (start: Long, endInclusive: Long) -> Boolean = { _, _ -> true },
) : ComicReaderSession {
    val loadedPages = mutableListOf<Int>()
    val prefetchedRanges = mutableListOf<Pair<Long, Long>>()
    val prefetchProtectedRanges = mutableListOf<List<LongRange>>()
    val reconciledPlanPages = mutableListOf<Int>()
    val reconciledPlanNetworkClasses = mutableListOf<Int>()
    val updateViewportNetworkClasses = mutableListOf<Int>()
    var cancelPrefetchesCalls = 0
    var closeCalls = 0

    override fun loadPageToFile(pageIndex: Int, outputFile: File): File {
        pageErrors[pageIndex]?.let { throw it }
        loadedPages += pageIndex
        outputFile.parentFile?.mkdirs()
        outputFile.writeBytes(byteArrayOf(pageIndex.toByte()))
        return outputFile
    }

    override fun updateViewport(pageIndex: Int, networkClass: Int) {
        updateViewportNetworkClasses += networkClass
    }

    override fun reconcilePrefetchPlan(
        pageIndex: Int,
        networkClass: Int,
        activeRanges: List<PlannedRemoteRange>,
        completedRanges: List<PlannedRemoteRange>,
        byteBudget: Long,
    ): ReconciledPrefetchPlan {
        reconciledPlanPages += pageIndex
        reconciledPlanNetworkClasses += networkClass
        return reconciledPlansForPage(pageIndex)
    }

    override fun prefetchRange(
        start: Long,
        endInclusive: Long,
        priority: Int,
        protectedRanges: List<LongRange>,
    ): Boolean {
        prefetchedRanges += start to endInclusive
        prefetchProtectedRanges += protectedRanges
        return prefetchResult(start, endInclusive)
    }

    override fun cancelPrefetches() {
        cancelPrefetchesCalls++
    }

    override fun close() {
        closeCalls++
    }
}

private fun plannedRange(start: Long, sizeBytes: Long, priority: Int): PlannedRemoteRange =
    PlannedRemoteRange(
        start = start,
        endInclusive = start + sizeBytes - 1,
        pages = listOf(priority),
        priority = priority,
    )

private fun plannedRangeForPage(pageIndex: Int): PlannedRemoteRange =
    PlannedRemoteRange(
        start = pageIndex * 1024L,
        endInclusive = pageIndex * 1024L + 511L,
        pages = listOf(pageIndex),
        priority = pageIndex,
    )

private fun plannedRangesForWindow(
    pageIndex: Int,
    pageCount: Int,
    forwardPages: Int,
): List<PlannedRemoteRange> =
    (listOf(pageIndex) + (1..forwardPages).map { pageIndex + it } + listOf(pageIndex - 1))
        .filter { it in 0 until pageCount }
        .distinct()
        .map(::plannedRangeForPage)

private fun PlannedRemoteRange.key(): Pair<Long, Long> =
    start to endInclusive
