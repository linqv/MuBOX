package com.example.comicdav.feature.reader

import com.example.comicdav.CollectingReaderLogSink
import com.example.comicdav.MainDispatcherRule
import com.example.comicdav.data.ComicDownloadCache
import com.example.comicdav.data.ReaderLoggingMode
import com.example.comicdav.nativebridge.ComicReaderSession
import com.example.comicdav.nativebridge.ComicNativeException
import com.example.comicdav.nativebridge.PlannedRemoteRange
import com.example.comicdav.nativebridge.RangeProviderRegistry
import com.example.comicdav.network.RemoteFileInfo
import com.example.comicdav.network.WebDavClient
import com.example.comicdav.network.WebDavItem
import java.io.File
import java.util.concurrent.Executors
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

@OptIn(ExperimentalCoroutinesApi::class)
class ReaderViewModelTest {
    @get:Rule
    val temp = TemporaryFolder()

    @get:Rule
    val mainDispatcher = MainDispatcherRule()

    private fun kotlinx.coroutines.test.TestScope.createTestViewModel(): ReaderViewModel {
        val dispatcher = StandardTestDispatcher(testScheduler)
        mainDispatcher.set(dispatcher)
        return ReaderViewModel(
            ioDispatcher = dispatcher,
            elapsedRealtimeMs = { testScheduler.currentTime },
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
    fun openSessionLimitsPlannedRangePrefetchBytes() = runTest {
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
        val viewModel = createTestViewModel()

        openDefaultSession(viewModel, session)
        runCurrent()

        assertEquals(ranges.take(3).map { it.key() }, session.prefetchedRanges)
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
    fun pagePrefetchCancellationWrappedByNativeErrorDoesNotLogFailure() = runTest {
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
        val viewModel = createTestViewModel()

        try {
            openDefaultSession(viewModel, session)
            runCurrent()
            advanceTimeBy(200)
            runCurrent()

            assertFalse(sink.lines.any { it.contains("prefetch_failed") })
        } finally {
            ReaderDiagnosticLog.clearSink()
            ReaderDiagnosticLog.setMode(ReaderLoggingMode.SUMMARY)
        }
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
        session.plannedRangePages.clear()

        viewModel.reportPageDemand(1, "pager_target")
        runCurrent()

        assertEquals(listOf(1), session.plannedRangePages)
    }

    @Test
    fun readyCurrentPageDemandAddsTrailingPlannedRangeForConfiguredWebDavWindow() = runTest {
        val forwardWindow = 12
        val session = RecordingComicSession(
            pageCount = 20,
            forwardPrefetchPageCount = forwardWindow,
            advancePrefetchOnPageDemand = true,
            plannedRangesForPage = { page ->
                plannedRangesForWindow(pageIndex = page, pageCount = 20, forwardPages = forwardWindow)
            },
        )
        val viewModel = createTestViewModel()

        openDefaultSession(viewModel, session)
        runCurrent()
        advanceTimeBy(200)
        runCurrent()
        assertEquals((0..12).toList(), session.loadedPages)
        assertEquals((0..12).map { plannedRangeForPage(it).key() }, session.prefetchedRanges)
        session.plannedRangePages.clear()
        session.prefetchedRanges.clear()

        viewModel.reportPageDemand(1, "pager_current")
        runCurrent()
        advanceTimeBy(200)
        runCurrent()

        assertEquals(listOf(1), session.plannedRangePages)
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
    fun remoteComicPreparationRunsEntirelyOnInjectedIoDispatcher() = runTest {
        val executor = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "comic-open-io")
        }
        val ioDispatcher = executor.asCoroutineDispatcher()
        val stages = mutableListOf<Pair<String, String>>()
        var registeredFileId: Long? = null
        val cacheDir = temp.root.resolve("remote-comic")
        val client = object : WebDavClient {
            override suspend fun list(path: String): List<WebDavItem> = emptyList()

            override suspend fun head(path: String): RemoteFileInfo {
                stages += "head" to Thread.currentThread().name
                return RemoteFileInfo(
                    path = path,
                    size = 9L,
                    etag = "\"v1\"",
                    lastModified = null,
                    supportsRange = true,
                )
            }

            override suspend fun readRange(path: String, start: Long, endInclusive: Long): ByteArray =
                ByteArray((endInclusive - start + 1L).toInt())

            override suspend fun download(path: String, target: File, onBytesRead: (Long) -> Unit): Long = 0L
        }
        val progressStore = object : ReadingProgressGateway {
            override suspend fun savePage(comicKey: String, pageIndex: Int) = Unit

            override suspend fun loadPage(comicKey: String): Int {
                stages += "progress" to Thread.currentThread().name
                return 0
            }
        }

        try {
            val useCase = OpenComicUseCase(
                accountId = "account",
                cache = ComicDownloadCache(cacheDir),
                progressStore = progressStore,
                ioDispatcher = ioDispatcher,
                openRemoteSession = { fileId, _, _, _, _, _, _ ->
                    registeredFileId = fileId
                    stages += "native-open" to Thread.currentThread().name
                    RecordingComicSession(pageCount = 1, forwardPrefetchPageCount = 0)
                },
            )

            useCase.open(client = client, remotePath = "/books/book.cbz")

            assertEquals(true, cacheDir.isDirectory)
            assertEquals(
                listOf(
                    "head" to "comic-open-io",
                    "native-open" to "comic-open-io",
                    "progress" to "comic-open-io",
                ),
                stages,
            )
        } finally {
            registeredFileId?.let(RangeProviderRegistry::unregister)
            ioDispatcher.close()
            executor.shutdownNow()
        }
    }
}


private class RecordingComicSession(
    override val pageCount: Int,
    override val forwardPrefetchPageCount: Int,
    private val plannedRanges: List<PlannedRemoteRange> = emptyList(),
    private val plannedRangesForPage: (Int) -> List<PlannedRemoteRange> = { plannedRanges },
    private val pageErrors: Map<Int, Throwable> = emptyMap(),
    override val advancePrefetchOnPageDemand: Boolean = false,
) : ComicReaderSession {
    val loadedPages = mutableListOf<Int>()
    val prefetchedRanges = mutableListOf<Pair<Long, Long>>()
    val plannedRangePages = mutableListOf<Int>()
    var cancelPrefetchesCalls = 0
    var closeCalls = 0

    override fun loadPageToFile(pageIndex: Int, outputFile: File): File {
        pageErrors[pageIndex]?.let { throw it }
        loadedPages += pageIndex
        outputFile.parentFile?.mkdirs()
        outputFile.writeBytes(byteArrayOf(pageIndex.toByte()))
        return outputFile
    }

    override fun plannedRanges(pageIndex: Int, networkClass: Int): List<PlannedRemoteRange> {
        plannedRangePages += pageIndex
        return plannedRangesForPage(pageIndex)
    }

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
