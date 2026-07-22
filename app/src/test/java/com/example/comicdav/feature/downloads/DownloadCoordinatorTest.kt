package com.example.comicdav.feature.downloads

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import com.example.comicdav.MainDispatcherRule
import com.example.comicdav.network.RemoteFileInfo
import com.example.comicdav.network.WebDavClient
import com.example.comicdav.network.WebDavItem
import java.io.Closeable
import java.io.File
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DownloadCoordinatorTest {
    @get:Rule
    val mainDispatcher = MainDispatcherRule()

    private val dispatcher = StandardTestDispatcher()
    private val client = UnusedWebDavClient()

    @Before
    fun setUp() {
        mainDispatcher.set(dispatcher)
    }

    @Test
    fun retainedViewModelStoreKeepsActiveDownloadAndProgressAcrossUiRecreation() = runTest(dispatcher) {
        val releaseDownload = CompletableDeferred<Unit>()
        val backend = object : StubDownloadBackend() {
            override suspend fun downloadComic(
                request: ComicDownloadRequest,
                client: WebDavClient,
                cancellation: DownloadCancellation,
                onProgress: (Long, Long) -> Unit,
            ) {
                onProgress(1_024L, 4_096L)
                releaseDownload.await()
            }
        }
        val store = ViewModelStore()
        val first = ViewModelProvider(store, coordinatorFactory(backend))[DownloadCoordinator::class.java]

        first.downloadComic(comicRequest()) { client }
        runCurrent()

        val beforeRecreation = first.state.value as DownloadState.Running
        assertEquals(1_024L, beforeRecreation.progress.downloadedBytes)

        // A configuration change creates a new UI owner but retains its ViewModelStore.
        val second = ViewModelProvider(store, throwingFactory())[DownloadCoordinator::class.java]
        assertSame(first, second)
        val afterRecreation = second.state.value as DownloadState.Running
        assertEquals(1_024L, afterRecreation.progress.downloadedBytes)

        releaseDownload.complete(Unit)
        advanceUntilIdle()
        assertEquals("book.cbz", (second.state.value as DownloadState.Succeeded).task.fileName)
        store.clear()
    }

    @Test
    fun cancelStopsBackendAndPublishesCancelledState() = runTest(dispatcher) {
        var backendCancelled = false
        var networkHandleClosed = false
        val backend = object : StubDownloadBackend() {
            override suspend fun downloadComic(
                request: ComicDownloadRequest,
                client: WebDavClient,
                cancellation: DownloadCancellation,
                onProgress: (Long, Long) -> Unit,
            ) {
                cancellation.register(Closeable { networkHandleClosed = true })
                try {
                    awaitCancellation()
                } finally {
                    backendCancelled = true
                }
            }
        }
        val coordinator = coordinator(backend)

        coordinator.downloadComic(comicRequest()) { client }
        runCurrent()
        coordinator.cancelActiveDownload()
        runCurrent()

        assertTrue(backendCancelled)
        assertTrue(networkHandleClosed)
        assertEquals("book.cbz", (coordinator.state.value as DownloadState.Cancelled).task.fileName)
    }

    @Test
    fun startingAnotherDownloadCancelsOldTaskWithoutOverwritingNewResult() = runTest(dispatcher) {
        var firstCancelled = false
        val lifecycleEvents = mutableListOf<String>()
        val backend = object : StubDownloadBackend() {
            override suspend fun downloadComic(
                request: ComicDownloadRequest,
                client: WebDavClient,
                cancellation: DownloadCancellation,
                onProgress: (Long, Long) -> Unit,
            ) {
                if (request.remotePath == "/book.cbz") {
                    try {
                        awaitCancellation()
                    } finally {
                        lifecycleEvents += "old-cleanup"
                        firstCancelled = true
                    }
                } else {
                    lifecycleEvents += "new-start"
                }
            }
        }
        val coordinator = coordinator(backend)

        coordinator.downloadComic(comicRequest()) { client }
        runCurrent()
        coordinator.downloadComic(
            comicRequest().copy(remotePath = "/next.cbz", fileName = "next.cbz"),
        ) { client }
        advanceUntilIdle()

        assertTrue(firstCancelled)
        assertEquals(listOf("old-cleanup", "new-start"), lifecycleEvents)
        val succeeded = coordinator.state.value as DownloadState.Succeeded
        assertEquals("next.cbz", succeeded.task.fileName)
        assertEquals("/next.cbz", succeeded.task.remotePath)
    }

    @Test
    fun missingLibraryClientPublishesActionableFailure() = runTest(dispatcher) {
        val coordinator = coordinator(StubDownloadBackend())

        coordinator.downloadComic(comicRequest().copy(origin = DownloadOrigin.LIBRARY)) { null }
        advanceUntilIdle()

        val failed = coordinator.state.value as DownloadState.Failed
        assertEquals("请先连接 account-1，再下载漫画", failed.message)
        assertEquals(DownloadOrigin.LIBRARY, failed.task.origin)
    }

    @Test
    fun terminalStateCanOnlyBeAcknowledgedByMatchingTask() = runTest(dispatcher) {
        val coordinator = coordinator(StubDownloadBackend())
        coordinator.downloadComic(comicRequest()) { client }
        advanceUntilIdle()
        val succeeded = coordinator.state.value as DownloadState.Succeeded

        coordinator.acknowledgeTerminalState(succeeded.task.id + 1L)
        assertSame(succeeded, coordinator.state.value)

        coordinator.acknowledgeTerminalState(succeeded.task.id)
        assertEquals(DownloadState.Idle, coordinator.state.value)
    }

    private fun coordinator(backend: DownloadBackend): DownloadCoordinator = DownloadCoordinator(
        backend = backend,
        reportFailure = { _, _ -> Unit },
        elapsedRealtime = { 0L },
    )

    private fun coordinatorFactory(backend: DownloadBackend): ViewModelProvider.Factory =
        object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = coordinator(backend) as T
        }

    private fun throwingFactory(): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            error("retained ViewModel should be returned without invoking this factory")
    }

    private fun comicRequest(): ComicDownloadRequest = ComicDownloadRequest(
        folderUri = "content://downloads/tree/root",
        accountId = "account-1",
        remotePath = "/book.cbz",
        fileName = "book.cbz",
        size = 4_096L,
        etag = "etag-1",
        lastModified = 123L,
        origin = DownloadOrigin.WEB_DAV_BROWSER,
    )
}

private open class StubDownloadBackend : DownloadBackend {
    override suspend fun downloadComic(
        request: ComicDownloadRequest,
        client: WebDavClient,
        cancellation: DownloadCancellation,
        onProgress: (Long, Long) -> Unit,
    ) = Unit

    override suspend fun downloadVideo(
        request: VideoDownloadRequest,
        client: WebDavClient,
        cancellation: DownloadCancellation,
        onProgress: (Long, Long) -> Unit,
    ) = Unit
}

private class UnusedWebDavClient : WebDavClient {
    override suspend fun list(path: String): List<WebDavItem> = error("unused")

    override suspend fun head(path: String): RemoteFileInfo = error("unused")

    override suspend fun readRange(path: String, start: Long, endInclusive: Long): ByteArray =
        error("unused")

    override suspend fun download(path: String, target: File, onBytesRead: (Long) -> Unit): Long =
        error("unused")
}
