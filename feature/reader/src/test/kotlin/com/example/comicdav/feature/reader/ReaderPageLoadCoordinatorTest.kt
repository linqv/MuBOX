package com.example.comicdav.feature.reader

import com.example.comicdav.core.ports.ComicReaderSession
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

@OptIn(ExperimentalCoroutinesApi::class)
class ReaderPageLoadCoordinatorTest {
    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun cacheHitSkipsSessionExtractionAndPruning() = runTest {
        val gate = RecordingSessionGate(generation = 4)
        val prunedFiles = mutableListOf<File>()
        val coordinator = ReaderPageLoadCoordinator(
            ioDispatcher = StandardTestDispatcher(testScheduler),
            sessionGate = gate,
            diagnostics = ReaderDiagnosticsTracker { testScheduler.currentTime },
            elapsedRealtimeMs = { testScheduler.currentTime },
            prunePageCache = { _, protectedFile, _ -> prunedFiles += protectedFile },
        )
        val context = pageLoadContext(generation = 4, cacheDir = temp.root)
        val cachedFile = ReaderPageCache.pageFile(temp.root, context.pageCacheKey, 1)
        cachedFile.writeBytes(byteArrayOf(9))
        val session = PageLoadRecordingSession(pageCount = 3)

        val files = coordinator.loadPages(
            session = session,
            context = context,
            pageIndexes = listOf(1, 1),
            reason = "select",
        )

        assertEquals(cachedFile, files[1])
        assertTrue(session.loadedPages.isEmpty())
        assertEquals(1, gate.lockCalls)
        assertTrue(prunedFiles.isEmpty())
    }

    @Test
    fun extractionUsesConfiguredCacheBudgetForPruning() = runTest {
        val gate = RecordingSessionGate(generation = 2)
        val pruneBudgets = mutableListOf<Long>()
        val coordinator = ReaderPageLoadCoordinator(
            ioDispatcher = StandardTestDispatcher(testScheduler),
            sessionGate = gate,
            diagnostics = ReaderDiagnosticsTracker { testScheduler.currentTime },
            elapsedRealtimeMs = { testScheduler.currentTime },
            prunePageCache = { _, _, maxBytes -> pruneBudgets += maxBytes },
        )
        coordinator.updatePageCacheMaxBytes(-10L)
        val session = PageLoadRecordingSession(pageCount = 2)

        coordinator.loadPages(
            session = session,
            context = pageLoadContext(generation = 2, cacheDir = temp.root),
            pageIndexes = listOf(0),
            reason = "initial",
        )

        assertEquals(listOf(0), session.loadedPages)
        assertEquals(listOf(0L), pruneBudgets)
    }

    @Test
    fun staleContextIsRejectedBeforeEnteringTheSessionLock() = runTest {
        val gate = RecordingSessionGate(generation = 8)
        val coordinator = ReaderPageLoadCoordinator(
            ioDispatcher = StandardTestDispatcher(testScheduler),
            sessionGate = gate,
            diagnostics = ReaderDiagnosticsTracker { testScheduler.currentTime },
            elapsedRealtimeMs = { testScheduler.currentTime },
            prunePageCache = { _, _, _ -> },
        )
        val session = PageLoadRecordingSession(pageCount = 1)

        val error = runCatching {
            coordinator.loadPages(
                session = session,
                context = pageLoadContext(generation = 7, cacheDir = temp.root),
                pageIndexes = listOf(0),
                reason = "initial",
            )
        }.exceptionOrNull()

        assertTrue(error is CancellationException)
        assertEquals(0, gate.lockCalls)
        assertTrue(session.loadedPages.isEmpty())
    }
}

private class RecordingSessionGate(var generation: Int) : ReaderSessionGenerationGate {
    var lockCalls = 0

    override fun isCurrent(generation: Int): Boolean = generation == this.generation

    override suspend fun <T> withSessionLock(action: () -> T): T {
        lockCalls++
        return action()
    }
}

private class PageLoadRecordingSession(
    override val pageCount: Int,
) : ComicReaderSession {
    val loadedPages = mutableListOf<Int>()

    override fun loadPageToFile(pageIndex: Int, outputFile: File): File {
        loadedPages += pageIndex
        outputFile.parentFile?.mkdirs()
        outputFile.writeBytes(byteArrayOf(pageIndex.toByte()))
        return outputFile
    }

    override fun close() = Unit
}

private fun pageLoadContext(generation: Int, cacheDir: File): ReaderPageLoadContext =
    ReaderPageLoadContext(
        generation = generation,
        cacheDir = cacheDir,
        pageCacheKey = "page-cache",
        transientPageKey = "page-cache#$generation",
    )
