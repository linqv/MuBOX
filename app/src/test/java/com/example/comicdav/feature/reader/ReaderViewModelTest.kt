package com.example.comicdav.feature.reader

import com.example.comicdav.data.ReaderLoggingMode
import com.example.comicdav.nativebridge.ComicReaderSession
import com.example.comicdav.nativebridge.ComicNativeException
import com.example.comicdav.nativebridge.PlannedRemoteRange
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.rules.TestWatcher
import org.junit.runner.Description

@OptIn(ExperimentalCoroutinesApi::class)
class ReaderViewModelTest {
    @get:Rule
    val temp = TemporaryFolder()

    @get:Rule
    val mainDispatcher = MainDispatcherRule()

    @Test
    fun selectPageCacheMissLoadsSelectedPageBeforeQueuedPrefetch() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        mainDispatcher.set(dispatcher)
        val session = RecordingComicSession(pageCount = 8, forwardPrefetchPageCount = 4)
        val viewModel = ReaderViewModel(
            ioDispatcher = dispatcher,
            elapsedRealtimeMs = { testScheduler.currentTime },
        )

        viewModel.openExistingSession(
            openedSession = session,
            cacheDir = temp.root,
            initialPage = 0,
            comicKey = "comic",
        )
        runCurrent()
        assertEquals(listOf(0), session.loadedPages)

        viewModel.selectPage(3)
        runCurrent()

        assertEquals(listOf(0, 3), session.loadedPages.take(2))
    }

    @Test
    fun openSessionLimitsExtractPrefetchAheadWindow() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        mainDispatcher.set(dispatcher)
        val session = RecordingComicSession(pageCount = 20, forwardPrefetchPageCount = 12)
        val viewModel = ReaderViewModel(
            ioDispatcher = dispatcher,
            elapsedRealtimeMs = { testScheduler.currentTime },
        )

        viewModel.openExistingSession(
            openedSession = session,
            cacheDir = temp.root,
            initialPage = 0,
            comicKey = "comic",
        )
        runCurrent()
        advanceTimeBy(200)
        runCurrent()

        assertEquals(listOf(0, 1, 2, 3), session.loadedPages)
    }

    @Test
    fun openSessionLimitsPlannedRangePrefetchBytes() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        mainDispatcher.set(dispatcher)
        val ranges = listOf(
            plannedRange(start = 0, sizeBytes = 16 * 1024 * 1024, priority = 0),
            plannedRange(start = 16 * 1024 * 1024, sizeBytes = 16 * 1024 * 1024, priority = 1),
            plannedRange(start = 32 * 1024 * 1024, sizeBytes = 16 * 1024 * 1024, priority = 2),
            plannedRange(start = 48 * 1024 * 1024, sizeBytes = 16 * 1024 * 1024, priority = 3),
        )
        val session = RecordingComicSession(
            pageCount = 20,
            forwardPrefetchPageCount = 12,
            plannedRanges = ranges,
        )
        val viewModel = ReaderViewModel(
            ioDispatcher = dispatcher,
            elapsedRealtimeMs = { testScheduler.currentTime },
        )

        viewModel.openExistingSession(
            openedSession = session,
            cacheDir = temp.root,
            initialPage = 0,
            comicKey = "comic",
        )
        runCurrent()

        assertEquals(ranges.take(3).map { it.key() }, session.prefetchedRanges)
    }

    @Test
    fun closeReaderCancelsSessionPrefetchesBeforeAsyncClose() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        mainDispatcher.set(dispatcher)
        val session = RecordingComicSession(pageCount = 2, forwardPrefetchPageCount = 0)
        val viewModel = ReaderViewModel(
            ioDispatcher = dispatcher,
            elapsedRealtimeMs = { testScheduler.currentTime },
        )

        viewModel.openExistingSession(
            openedSession = session,
            cacheDir = temp.root,
            initialPage = 0,
            comicKey = "comic",
        )
        runCurrent()

        viewModel.closeReader()

        assertEquals(1, session.cancelPrefetchesCalls)
        assertEquals(0, session.closeCalls)
        runCurrent()
        assertEquals(1, session.closeCalls)
    }

    @Test
    fun pagePrefetchCancellationWrappedByNativeErrorDoesNotLogFailure() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        mainDispatcher.set(dispatcher)
        val sink = CollectingReaderLogSink()
        ReaderDiagnosticLog.setSink(sink)
        ReaderDiagnosticLog.setMode(ReaderLoggingMode.SUMMARY)
        val session = RecordingComicSession(
            pageCount = 4,
            forwardPrefetchPageCount = 1,
            pageErrors = mapOf(
                1 to ComicNativeException(
                    "range callback readRange failed for file 2 bytes 10-20: " +
                        "java.util.concurrent.CancellationException: range fetch cancelled",
                ),
            ),
        )
        val viewModel = ReaderViewModel(
            ioDispatcher = dispatcher,
            elapsedRealtimeMs = { testScheduler.currentTime },
        )

        try {
            viewModel.openExistingSession(
                openedSession = session,
                cacheDir = temp.root,
                initialPage = 0,
                comicKey = "comic",
            )
            runCurrent()
            advanceTimeBy(200)
            runCurrent()

            assertFalse(sink.lines.any { it.contains("prefetch_failed") })
        } finally {
            ReaderDiagnosticLog.clearSink()
            ReaderDiagnosticLog.setMode(ReaderLoggingMode.SUMMARY)
        }
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class MainDispatcherRule : TestWatcher() {
    fun set(dispatcher: TestDispatcher) {
        Dispatchers.setMain(dispatcher)
    }

    override fun finished(description: Description) {
        Dispatchers.resetMain()
    }
}

private class RecordingComicSession(
    override val pageCount: Int,
    override val forwardPrefetchPageCount: Int,
    private val plannedRanges: List<PlannedRemoteRange> = emptyList(),
    private val pageErrors: Map<Int, Throwable> = emptyMap(),
) : ComicReaderSession {
    val loadedPages = mutableListOf<Int>()
    val prefetchedRanges = mutableListOf<Pair<Long, Long>>()
    var cancelPrefetchesCalls = 0
    var closeCalls = 0

    override fun loadPageToFile(pageIndex: Int, outputFile: File): File {
        pageErrors[pageIndex]?.let { throw it }
        loadedPages += pageIndex
        outputFile.parentFile?.mkdirs()
        outputFile.writeBytes(byteArrayOf(pageIndex.toByte()))
        return outputFile
    }

    override fun plannedRanges(pageIndex: Int, networkClass: Int): List<PlannedRemoteRange> =
        plannedRanges

    override fun prefetchRange(
        start: Long,
        endInclusive: Long,
        priority: Int,
        protectedRanges: List<LongRange>,
    ): Boolean {
        prefetchedRanges += start to endInclusive
        return true
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

private fun PlannedRemoteRange.key(): Pair<Long, Long> =
    start to endInclusive

private class CollectingReaderLogSink : ReaderLogSink {
    val lines = mutableListOf<String>()

    override fun log(line: String) {
        lines += line
    }

    override fun logBlocking(line: String) {
        lines += line
    }
}
