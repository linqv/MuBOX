package com.example.comicdav.feature.downloads

import android.content.Context
import android.net.Uri
import android.os.SystemClock
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.comicdav.core.model.transfer.ComicDownloadRequest
import com.example.comicdav.core.model.transfer.DownloadMediaType
import com.example.comicdav.core.model.transfer.DownloadOrigin
import com.example.comicdav.core.model.transfer.DownloadTask
import com.example.comicdav.core.model.transfer.TransferProgress
import com.example.comicdav.core.model.transfer.VideoDownloadRequest
import com.example.comicdav.data.DownloadRecordStore
import com.example.comicdav.data.VideoDownloadRecord
import com.example.comicdav.data.VideoDownloadStore
import com.example.comicdav.core.remote.RemoteFileInfo
import com.example.comicdav.core.remote.WebDavClient
import java.io.Closeable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

internal sealed interface DownloadState {
    data object Idle : DownloadState

    data class Running(
        val task: DownloadTask,
        val progress: TransferProgress,
    ) : DownloadState

    data class Succeeded(
        val task: DownloadTask,
        val message: String,
    ) : DownloadState

    data class Failed(
        val task: DownloadTask,
        val message: String,
        val error: Throwable,
    ) : DownloadState

    data class Cancelled(
        val task: DownloadTask,
        val message: String = "已取消下载",
    ) : DownloadState
}

internal val DownloadState.activeProgress: TransferProgress?
    get() = (this as? DownloadState.Running)?.progress

/**
 * Owns the single in-process download slot. Because this is an Activity ViewModel, its job and
 * progress survive Activity recreation while the actual backend only retains application-scoped
 * dependencies.
 */
internal class DownloadCoordinator(
    private val backend: DownloadBackend,
    private val reportFailure: (String, Throwable) -> Unit = { _, _ -> },
    private val elapsedRealtime: () -> Long = SystemClock::elapsedRealtime,
) : ViewModel() {
    private val mutableState = MutableStateFlow<DownloadState>(DownloadState.Idle)
    val state: StateFlow<DownloadState> = mutableState.asStateFlow()

    private var nextTaskId = 0L
    @Volatile
    private var activeTaskId: Long? = null
    private var activeJob: Job? = null
    private var activeCancellation: DownloadCancellation? = null

    fun downloadComic(
        request: ComicDownloadRequest,
        clientProvider: suspend () -> WebDavClient?,
    ) {
        start(
            task = newTask(
                fileName = request.fileName,
                remotePath = request.remotePath,
                mediaType = DownloadMediaType.COMIC,
                origin = request.origin,
                totalBytes = request.size,
            ),
            failureMessage = "下载到本地失败",
            diagnosticName = if (request.origin == DownloadOrigin.LIBRARY) {
                "download_remote_comic_failed"
            } else {
                "download_webdav_comic_failed"
            },
        ) { cancellation, onProgress ->
            val client = clientProvider()
                ?: error("请先连接 ${request.accountId}，再下载漫画")
            backend.downloadComic(request, client, cancellation, onProgress)
        }
    }

    fun downloadVideo(
        request: VideoDownloadRequest,
        client: WebDavClient,
    ) {
        start(
            task = newTask(
                fileName = request.fileName,
                remotePath = request.remotePath,
                mediaType = DownloadMediaType.VIDEO,
                origin = request.origin,
                totalBytes = request.size,
            ),
            failureMessage = "下载视频失败",
            diagnosticName = "download_webdav_video_failed",
        ) { cancellation, onProgress ->
            backend.downloadVideo(request, client, cancellation, onProgress)
        }
    }

    fun cancelActiveDownload() {
        val running = mutableState.value as? DownloadState.Running ?: return
        activeTaskId = null
        val job = activeJob
        activeJob = null
        val cancellation = activeCancellation
        activeCancellation = null
        mutableState.value = DownloadState.Cancelled(running.task)
        cancellation?.cancel()
        job?.cancel()
    }

    fun acknowledgeTerminalState(taskId: Long) {
        val current = mutableState.value
        val currentTaskId = when (current) {
            is DownloadState.Succeeded -> current.task.id
            is DownloadState.Failed -> current.task.id
            is DownloadState.Cancelled -> current.task.id
            DownloadState.Idle,
            is DownloadState.Running,
            -> null
        }
        if (currentTaskId == taskId) {
            mutableState.value = DownloadState.Idle
        }
    }

    private fun start(
        task: DownloadTask,
        failureMessage: String,
        diagnosticName: String,
        download: suspend (
            cancellation: DownloadCancellation,
            onProgress: (downloadedBytes: Long, totalBytes: Long) -> Unit,
        ) -> Unit,
    ) {
        // Invalidate the old task before cancelling it so its completion handlers cannot replace
        // the state of the new task.
        val previousJob = activeJob
        activeTaskId = task.id
        activeCancellation?.cancel()
        previousJob?.cancel()
        val cancellation = DownloadCancellation()
        activeCancellation = cancellation
        mutableState.value = DownloadState.Running(
            task = task,
            progress = TransferProgress(
                downloadedBytes = 0L,
                totalBytes = task.totalBytes?.coerceAtLeast(0L) ?: 0L,
            ),
        )

        val job = viewModelScope.launch(start = CoroutineStart.LAZY) {
            val throttler = DownloadProgressThrottler()
            try {
                // The old task owns the same deterministic .tmp name. Wait until its cancellation
                // cleanup is finished before the replacement is allowed to create a new temp file.
                previousJob?.join()
                currentCoroutineContext().ensureActive()
                download(cancellation) { downloadedBytes, totalBytes ->
                    if (
                        activeTaskId == task.id &&
                        throttler.shouldReport(downloadedBytes, totalBytes, elapsedRealtime())
                    ) {
                        mutableState.value = DownloadState.Running(
                            task = task,
                            progress = TransferProgress(downloadedBytes, totalBytes),
                        )
                    }
                }
                if (activeTaskId == task.id) {
                    mutableState.value = DownloadState.Succeeded(
                        task = task,
                        message = "已下载 ${task.fileName} 到数据文件夹",
                    )
                }
            } catch (error: CancellationException) {
                if (activeTaskId == task.id) {
                    mutableState.value = DownloadState.Cancelled(task)
                }
                throw error
            } catch (error: Throwable) {
                if (activeTaskId == task.id) {
                    reportFailure("$diagnosticName path=${task.remotePath}", error)
                    mutableState.value = DownloadState.Failed(
                        task = task,
                        message = error.message ?: failureMessage,
                        error = error,
                    )
                }
            } finally {
                cancellation.release()
                if (activeTaskId == task.id) {
                    activeTaskId = null
                    activeJob = null
                    activeCancellation = null
                }
            }
        }
        activeJob = job
        job.start()
    }

    override fun onCleared() {
        activeTaskId = null
        activeCancellation?.cancel()
        activeCancellation = null
        activeJob?.cancel()
        activeJob = null
        super.onCleared()
    }

    private fun newTask(
        fileName: String,
        remotePath: String,
        mediaType: DownloadMediaType,
        origin: DownloadOrigin,
        totalBytes: Long?,
    ): DownloadTask = DownloadTask(
        id = ++nextTaskId,
        fileName = fileName,
        remotePath = remotePath,
        mediaType = mediaType,
        origin = origin,
        totalBytes = totalBytes,
    )

    internal class Factory(
        private val backend: DownloadBackend,
        private val reportFailure: (String, Throwable) -> Unit = { _, _ -> },
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass.isAssignableFrom(DownloadCoordinator::class.java))
            return DownloadCoordinator(backend, reportFailure = reportFailure) as T
        }
    }
}

internal class DownloadCancellation {
    private val lock = Any()
    private val closeables = mutableListOf<Closeable>()
    private var acceptingRegistrations = true

    fun register(closeable: Closeable) {
        val closeImmediately = synchronized(lock) {
            if (!acceptingRegistrations) {
                true
            } else {
                closeables += closeable
                false
            }
        }
        if (closeImmediately) {
            runCatching { closeable.close() }
        }
    }

    fun cancel() {
        val handles = synchronized(lock) {
            if (!acceptingRegistrations) return
            acceptingRegistrations = false
            closeables.toList().also { closeables.clear() }
        }
        handles.forEach { closeable ->
            runCatching { closeable.close() }
        }
    }

    fun release() {
        synchronized(lock) {
            acceptingRegistrations = false
            closeables.clear()
        }
    }
}

internal interface DownloadBackend {
    suspend fun downloadComic(
        request: ComicDownloadRequest,
        client: WebDavClient,
        cancellation: DownloadCancellation,
        onProgress: (downloadedBytes: Long, totalBytes: Long) -> Unit,
    )

    suspend fun downloadVideo(
        request: VideoDownloadRequest,
        client: WebDavClient,
        cancellation: DownloadCancellation,
        onProgress: (downloadedBytes: Long, totalBytes: Long) -> Unit,
    )
}

internal class AndroidDownloadBackend(
    context: Context,
    private val downloadRecordStore: DownloadRecordStore,
    private val videoDownloadStore: VideoDownloadStore,
    private val nowMillis: () -> Long = System::currentTimeMillis,
) : DownloadBackend {
    private val applicationContext = context.applicationContext

    override suspend fun downloadComic(
        request: ComicDownloadRequest,
        client: WebDavClient,
        cancellation: DownloadCancellation,
        onProgress: (downloadedBytes: Long, totalBytes: Long) -> Unit,
    ) {
        val info = request.remoteFileInfo(client)
        downloadWebDavComicRecordToDataFolder(
            context = applicationContext,
            folderTreeUri = Uri.parse(request.folderUri),
            client = client,
            accountId = request.accountId,
            remotePath = request.remotePath,
            fileName = request.fileName,
            info = info,
            downloadedAtMillis = nowMillis(),
            registerCancellation = cancellation::register,
            onProgress = onProgress,
            onCommitted = downloadRecordStore::addRecord,
        )
    }

    override suspend fun downloadVideo(
        request: VideoDownloadRequest,
        client: WebDavClient,
        cancellation: DownloadCancellation,
        onProgress: (downloadedBytes: Long, totalBytes: Long) -> Unit,
    ) {
        val info = request.remoteFileInfo(client)
        downloadWebDavVideoToDataFolder(
            context = applicationContext,
            folderTreeUri = Uri.parse(request.folderUri),
            client = client,
            accountId = request.accountId,
            remotePath = request.remotePath,
            fileName = request.fileName,
            expectedSize = info.size,
            registerCancellation = cancellation::register,
            onProgress = onProgress,
            onCommitted = { result ->
                videoDownloadStore.addRecord(
                    VideoDownloadRecord(
                        fileName = request.fileName,
                        accountId = request.accountId,
                        remotePath = request.remotePath,
                        localUri = result.localUri,
                        sizeBytes = result.sizeBytes,
                        downloadedAtMillis = nowMillis(),
                    ),
                )
            },
        )
    }
}

private suspend fun ComicDownloadRequest.remoteFileInfo(client: WebDavClient): RemoteFileInfo =
    size?.let { knownSize ->
        RemoteFileInfo(
            path = remotePath,
            size = knownSize,
            etag = etag,
            lastModified = lastModified,
            supportsRange = true,
        )
    } ?: client.head(remotePath)

private suspend fun VideoDownloadRequest.remoteFileInfo(client: WebDavClient): RemoteFileInfo =
    size?.let { knownSize ->
        RemoteFileInfo(
            path = remotePath,
            size = knownSize,
            etag = etag,
            lastModified = lastModified,
            supportsRange = true,
        )
    } ?: client.head(remotePath)
