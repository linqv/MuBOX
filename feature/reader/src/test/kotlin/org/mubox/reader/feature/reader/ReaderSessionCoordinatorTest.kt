package org.mubox.reader.feature.reader

import org.mubox.reader.core.ports.ComicReaderSession
import java.io.File
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ReaderSessionCoordinatorTest {
    @Test
    fun closeAdvancesGenerationCancelsOwnedWorkAndClosesSessionAsynchronously() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val clearedTransientPages = mutableListOf<Pair<File, String>>()
        val coordinator = ReaderSessionCoordinator(
            ioDispatcher = dispatcher,
            clearTransientPages = { cacheDir, readerKey ->
                clearedTransientPages += cacheDir to readerKey
            },
        )
        val cacheDir = File("reader-session-test-cache")
        val opening = requireNotNull(
            coordinator.beginOpening(
                cacheDir = cacheDir,
                comicKey = "comic",
                pageCacheKey = "page-cache",
                readerKeyBase = "comic",
            ),
        )
        val session = CoordinatorRecordingSession()
        assertTrue(coordinator.activate(opening, session))

        val remoteJob = backgroundScope.launch(dispatcher) { awaitCancellation() }
        coordinator.trackRemoteOpen(remoteJob)
        lateinit var viewportJob: Job
        coordinator.replaceViewportJob {
            backgroundScope.launch(dispatcher) { awaitCancellation() }
                .also { viewportJob = it }
        }

        var closingSession: ComicReaderSession? = null
        var dependentWorkCancelled = false
        coordinator.closeCurrentSession(
            beforeRemoteCancellation = { closingSession = it },
            cancelDependentWork = { dependentWorkCancelled = true },
        )

        assertSame(session, closingSession)
        assertTrue(dependentWorkCancelled)
        assertTrue(remoteJob.isCancelled)
        assertTrue(viewportJob.isCancelled)
        assertEquals(1, session.cancelPrefetchesCalls)
        assertEquals(0, session.closeCalls)
        assertEquals(1, coordinator.generation)
        assertNull(coordinator.activeSession)

        runCurrent()

        assertEquals(1, session.closeCalls)
        assertEquals(listOf(cacheDir to "page-cache#0"), clearedTransientPages)
    }

    @Test
    fun staleOpeningCannotBecomeTheActiveSession() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val coordinator = ReaderSessionCoordinator(
            ioDispatcher = dispatcher,
            clearTransientPages = { _, _ -> },
        )
        val opening = requireNotNull(
            coordinator.beginOpening(
                cacheDir = File("reader-session-stale-cache"),
                comicKey = "comic",
                pageCacheKey = "comic",
                readerKeyBase = "comic",
            ),
        )
        coordinator.closeCurrentSession(
            beforeRemoteCancellation = {},
            cancelDependentWork = {},
        )
        val staleSession = CoordinatorRecordingSession()

        assertFalse(coordinator.activate(opening, staleSession))
        assertNull(coordinator.activeSession)
        runCurrent()
        assertEquals(1, staleSession.closeCalls)
    }
}

private class CoordinatorRecordingSession : ComicReaderSession {
    override val pageCount: Int = 1
    var cancelPrefetchesCalls = 0
    var closeCalls = 0

    override fun loadPageToFile(pageIndex: Int, outputFile: File): File = outputFile

    override fun cancelPrefetches() {
        cancelPrefetchesCalls++
    }

    override fun close() {
        closeCalls++
    }
}
