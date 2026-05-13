package com.example.comicdav.feature.reader

import com.example.comicdav.nativebridge.ComicReaderSession
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.rules.TemporaryFolder

@OptIn(ExperimentalCoroutinesApi::class)
class ReaderViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val temp = TemporaryFolder().apply { create() }

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun openLocalLoadsCurrentPageAndPrefetchesForwardWindow() = runTest(dispatcher) {
        val session = FakeReaderSession(pageCount = 4)
        val viewModel = ReaderViewModel(
            openSession = { session },
            ioDispatcher = dispatcher,
        )

        viewModel.openLocal("/tmp/book.cbz", temp.root)
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(4, viewModel.uiState.pageCount)
        assertEquals(0, viewModel.uiState.currentPage)
        assertEquals(listOf(0, 1, 2, 3), session.loadedPages)
        assertEquals(setOf(0, 1, 2, 3), viewModel.uiState.pageFiles.keys)
    }

    @Test
    fun selectPageLoadsPreviousCurrentAndNextPage() = runTest(dispatcher) {
        val session = FakeReaderSession(pageCount = 5)
        val viewModel = ReaderViewModel(
            openSession = { session },
            ioDispatcher = dispatcher,
        )
        viewModel.openLocal("/tmp/book.cbz", temp.root)
        dispatcher.scheduler.advanceUntilIdle()

        viewModel.selectPage(2)
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(2, viewModel.uiState.currentPage)
        assertTrue(session.loadedPages.containsAll(listOf(1, 2, 3)))
        assertTrue(viewModel.uiState.pageFiles.keys.containsAll(setOf(1, 2, 3)))
    }

    @Test
    fun selectPageLoadsCurrentPageBeforeNeighbors() = runTest(dispatcher) {
        val session = FakeReaderSession(pageCount = 7)
        val viewModel = ReaderViewModel(
            openSession = { session },
            ioDispatcher = dispatcher,
        )
        viewModel.openLocal("/tmp/book.cbz", temp.root)
        dispatcher.scheduler.advanceUntilIdle()
        session.loadedPages.clear()

        viewModel.selectPage(5)
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(5, session.loadedPages.first())
    }

    @Test
    fun pageCacheFilesAreScopedByComicKey() = runTest(dispatcher) {
        val firstSession = FakeReaderSession(pageCount = 1)
        val secondSession = FakeReaderSession(pageCount = 1)
        val viewModel = ReaderViewModel(ioDispatcher = dispatcher)

        viewModel.openExistingSession(firstSession, temp.root, initialPage = 0, comicKey = "first")
        dispatcher.scheduler.advanceUntilIdle()
        val firstPath = viewModel.uiState.pageFiles.getValue(0).absolutePath
        viewModel.closeReader()

        viewModel.openExistingSession(secondSession, temp.root, initialPage = 0, comicKey = "second")
        dispatcher.scheduler.advanceUntilIdle()
        val secondPath = viewModel.uiState.pageFiles.getValue(0).absolutePath

        assertTrue(firstPath.contains("first"))
        assertTrue(secondPath.contains("second"))
        assertTrue(firstPath != secondPath)
    }

    @Test
    fun selectPageSavesReadingProgressWhenComicKeyIsPresent() = runTest(dispatcher) {
        val session = FakeReaderSession(pageCount = 5)
        val savedPages = mutableListOf<Pair<String, Int>>()
        val viewModel = ReaderViewModel(
            openSession = { session },
            ioDispatcher = dispatcher,
            savePage = { key, page -> savedPages += key to page },
        )
        viewModel.openLocal("/tmp/book.cbz", temp.root, comicKey = "comic-key")
        dispatcher.scheduler.advanceUntilIdle()

        viewModel.selectPage(2)
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(listOf("comic-key" to 2), savedPages)
    }

    @Test
    fun clearedClosesOpenSession() = runTest(dispatcher) {
        val session = FakeReaderSession(pageCount = 1)
        val viewModel = ReaderViewModel(
            openSession = { session },
            ioDispatcher = dispatcher,
        )
        viewModel.openLocal("/tmp/book.cbz", temp.root)
        dispatcher.scheduler.advanceUntilIdle()

        viewModel.closeReader()
        dispatcher.scheduler.advanceUntilIdle()

        assertTrue(session.closed)
    }

    @Test
    fun openRemoteShowsReaderLoadingStateBeforeRemoteOpenCompletes() = runTest(dispatcher) {
        val releaseOpen = CompletableDeferred<Unit>()
        val session = FakeReaderSession(pageCount = 1)
        val viewModel = ReaderViewModel(ioDispatcher = dispatcher)

        viewModel.openRemote(temp.root) {
            releaseOpen.await()
            OpenComicResult(
                comicKey = "remote-book",
                localFile = temp.newFile("remote.cbz"),
                session = session,
                initialPage = 0,
            )
        }

        assertTrue(viewModel.uiState.isLoading)
        assertEquals(0, viewModel.uiState.pageCount)

        releaseOpen.complete(Unit)
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(1, viewModel.uiState.pageCount)
        assertEquals(0, viewModel.uiState.currentPage)
    }

    @Test
    fun closeReaderDoesNotBlockCallerWhileNativeCloseRuns() = runTest(dispatcher) {
        val executor = java.util.concurrent.Executors.newSingleThreadExecutor()
        val ioDispatcher = executor.asCoroutineDispatcher()
        val closeStarted = CountDownLatch(1)
        val releaseClose = CountDownLatch(1)
        val closeFinished = CountDownLatch(1)
        val session = BlockingCloseSession(closeStarted, releaseClose, closeFinished)
        val viewModel = ReaderViewModel(ioDispatcher = ioDispatcher)

        viewModel.openExistingSession(session, temp.root, initialPage = 0, comicKey = "slow-close")
        waitUntil(timeoutMs = 1_000) {
            dispatcher.scheduler.advanceUntilIdle()
            viewModel.uiState.pageCount == 1
        }
        val startedAt = System.nanoTime()

        viewModel.closeReader()
        val elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt)

        assertTrue("closeReader blocked for ${elapsedMs}ms", elapsedMs < 100)
        assertTrue(closeStarted.await(1, TimeUnit.SECONDS))
        releaseClose.countDown()
        assertTrue(closeFinished.await(1, TimeUnit.SECONDS))
        ioDispatcher.close()
        executor.shutdown()
    }

    private class FakeReaderSession(
        override val pageCount: Int,
    ) : ComicReaderSession {
        val loadedPages = mutableListOf<Int>()
        var closed = false

        override fun loadPageToFile(pageIndex: Int, outputFile: File): File {
            loadedPages += pageIndex
            outputFile.writeText("page-$pageIndex")
            return outputFile
        }

        override fun close() {
            closed = true
        }
    }

    private class BlockingCloseSession(
        private val closeStarted: CountDownLatch,
        private val releaseClose: CountDownLatch,
        private val closeFinished: CountDownLatch,
    ) : ComicReaderSession {
        override val pageCount: Int = 1

        override fun loadPageToFile(pageIndex: Int, outputFile: File): File {
            outputFile.writeText("page-$pageIndex")
            return outputFile
        }

        override fun close() {
            closeStarted.countDown()
            releaseClose.await(2, TimeUnit.SECONDS)
            closeFinished.countDown()
        }
    }

    private fun waitUntil(timeoutMs: Long, condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
        while (!condition() && System.nanoTime() < deadline) {
            Thread.sleep(10)
        }
        assertTrue("condition not met within ${timeoutMs}ms", condition())
    }
}
