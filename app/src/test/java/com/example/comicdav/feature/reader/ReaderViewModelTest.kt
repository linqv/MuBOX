package com.example.comicdav.feature.reader

import com.example.comicdav.nativebridge.ComicReaderSession
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.Assert.assertEquals
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
) : ComicReaderSession {
    val loadedPages = mutableListOf<Int>()
    var cancelPrefetchesCalls = 0
    var closeCalls = 0

    override fun loadPageToFile(pageIndex: Int, outputFile: File): File {
        loadedPages += pageIndex
        outputFile.parentFile?.mkdirs()
        outputFile.writeBytes(byteArrayOf(pageIndex.toByte()))
        return outputFile
    }

    override fun cancelPrefetches() {
        cancelPrefetchesCalls++
    }

    override fun close() {
        closeCalls++
    }
}
