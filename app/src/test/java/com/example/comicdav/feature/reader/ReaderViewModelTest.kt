package com.example.comicdav.feature.reader

import com.example.comicdav.nativebridge.ComicReaderSession
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
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
    fun openLocalLoadsCurrentAndNextPage() = runTest(dispatcher) {
        val session = FakeReaderSession(pageCount = 4)
        val viewModel = ReaderViewModel(
            openSession = { session },
            ioDispatcher = dispatcher,
        )

        viewModel.openLocal("/tmp/book.cbz", temp.root)
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(4, viewModel.uiState.pageCount)
        assertEquals(0, viewModel.uiState.currentPage)
        assertEquals(listOf(0, 1), session.loadedPages)
        assertEquals(setOf(0, 1), viewModel.uiState.pageFiles.keys)
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
        val session = FakeReaderSession(pageCount = 5)
        val viewModel = ReaderViewModel(
            openSession = { session },
            ioDispatcher = dispatcher,
        )
        viewModel.openLocal("/tmp/book.cbz", temp.root)
        dispatcher.scheduler.advanceUntilIdle()
        session.loadedPages.clear()

        viewModel.selectPage(2)
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(2, session.loadedPages.first())
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

        assertTrue(session.closed)
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
}
