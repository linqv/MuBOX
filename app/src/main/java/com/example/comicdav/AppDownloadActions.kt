package com.example.comicdav

import android.content.Context
import android.net.Uri
import com.example.comicdav.data.DownloadRecord
import com.example.comicdav.data.VideoDownloadRecord
import com.example.comicdav.data.library.LibraryItemWithSources
import com.example.comicdav.feature.downloads.ComicDownloadRequest
import com.example.comicdav.feature.downloads.DownloadMediaType
import com.example.comicdav.feature.downloads.DownloadOrigin
import com.example.comicdav.feature.downloads.DownloadState
import com.example.comicdav.feature.downloads.VideoDownloadRequest
import com.example.comicdav.network.WebDavItem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

internal data class AppDownloadActionCallbacks(
    val setError: (String?) -> Unit,
    val setActionMessage: (String?) -> Unit,
    val clearSelectionIf: (predicate: (AppSelection) -> Boolean) -> Unit,
)

internal class AppDownloadActions(
    private val context: Context,
    private val scope: CoroutineScope,
    private val dataFolderUri: String?,
    private val container: AppContainer,
    private val viewModels: AppViewModels,
    private val callbacks: AppDownloadActionCallbacks,
) {
    private val coordinator = viewModels.downloads
    private val webDavViewModel = viewModels.webDav
    private val libraryViewModel = viewModels.library
    private val fileDirectoryViewModel = viewModels.fileDirectory

    fun handleState(state: DownloadState) {
        when (state) {
            is DownloadState.Succeeded -> {
                when (state.task.origin) {
                    DownloadOrigin.WEB_DAV_BROWSER -> {
                        callbacks.setError(null)
                        callbacks.setActionMessage(state.message)
                        if (state.task.mediaType == DownloadMediaType.VIDEO) {
                            callbacks.clearSelectionIf { it is AppSelection.WebDavFile }
                        }
                        fileDirectoryViewModel.showMessage(state.message)
                    }
                    DownloadOrigin.LIBRARY -> libraryViewModel.showMessage(state.message)
                }
                coordinator.acknowledgeTerminalState(state.task.id)
            }
            is DownloadState.Failed -> {
                when (state.task.origin) {
                    DownloadOrigin.WEB_DAV_BROWSER -> {
                        callbacks.setActionMessage(null)
                        callbacks.setError(state.message)
                        fileDirectoryViewModel.showError(state.message)
                    }
                    DownloadOrigin.LIBRARY -> libraryViewModel.showError(state.message)
                }
                coordinator.acknowledgeTerminalState(state.task.id)
            }
            is DownloadState.Cancelled -> {
                callbacks.setError(null)
                callbacks.setActionMessage(state.message)
                coordinator.acknowledgeTerminalState(state.task.id)
            }
            DownloadState.Idle,
            is DownloadState.Running,
            -> Unit
        }
    }

    fun cancelActiveDownload() {
        callbacks.setError(null)
        callbacks.setActionMessage(null)
        coordinator.cancelActiveDownload()
    }

    fun downloadWebDavComic(item: WebDavItem) {
        val client = webDavViewModel.activeClient()
        val accountId = currentWebDavAccountId()
        if (client == null) {
            callbacks.setError("请先连接 WebDAV，再下载漫画")
            callbacks.setActionMessage(null)
            return
        }
        val folderUri = requireDataFolder("请先选择 MuBOX 数据文件夹，再下载漫画") ?: return
        clearMessages()
        coordinator.downloadComic(
            request = ComicDownloadRequest(
                folderUri = folderUri,
                accountId = accountId,
                remotePath = item.path,
                fileName = item.name,
                size = item.size,
                etag = item.etag,
                lastModified = item.lastModified,
                origin = DownloadOrigin.WEB_DAV_BROWSER,
            ),
        ) {
            client
        }
    }

    fun downloadWebDavVideo(item: WebDavItem) {
        val client = webDavViewModel.activeClient()
        val accountId = currentWebDavAccountId()
        if (client == null) {
            callbacks.setError("请先连接 WebDAV，再下载视频")
            callbacks.setActionMessage(null)
            return
        }
        val folderUri = requireDataFolder("请先选择 MuBOX 数据文件夹，再下载视频") ?: return
        clearMessages()
        coordinator.downloadVideo(
            request = VideoDownloadRequest(
                folderUri = folderUri,
                accountId = accountId,
                remotePath = item.path,
                fileName = item.name,
                size = item.size,
                etag = item.etag,
                lastModified = item.lastModified,
            ),
            client = client,
        )
    }

    fun downloadLibraryWebDavComic(item: LibraryItemWithSources) {
        val source = item.webDavSource ?: run {
            libraryViewModel.showError("本地漫画无需下载")
            return
        }
        val folderUri = dataFolderUri?.takeIf { it.isNotBlank() } ?: run {
            libraryViewModel.showError("请先选择 MuBOX 数据文件夹，再下载漫画")
            return
        }
        clearMessages()
        coordinator.downloadComic(
            request = ComicDownloadRequest(
                folderUri = folderUri,
                accountId = source.accountId,
                remotePath = source.remotePath,
                fileName = source.fileName,
                size = source.size,
                etag = source.etag,
                lastModified = source.lastModified,
                origin = DownloadOrigin.LIBRARY,
            ),
        ) {
            container.webDavClientProvider.clientFor(source.accountId)
        }
    }

    fun removeComicRecord(record: DownloadRecord) {
        scope.launch {
            container.downloadRecordStore.removeRecord(record)
        }
    }

    fun deleteComicFile(record: DownloadRecord) {
        scope.launch {
            val uriText = downloadLocalUriTextOrNull(record.localUri)
            if (uriText == null) {
                container.downloadRecordStore.removeRecord(record)
                callbacks.setError(null)
                callbacks.setActionMessage("已从列表移除 ${record.fileName}（缺少本地文件位置）")
                return@launch
            }
            val shouldRemoveRecord = deleteDownloadDocumentAndShouldRemoveRecord(
                context = context,
                uri = Uri.parse(uriText),
                diagnosticName = "delete_comic_download_file",
            )
            if (shouldRemoveRecord) {
                container.downloadRecordStore.removeRecord(record)
                callbacks.setError(null)
                callbacks.setActionMessage("已删除 ${record.fileName}")
            } else {
                callbacks.setError("无法删除 ${record.fileName}，下载记录已保留")
            }
        }
    }

    fun deleteVideoFile(record: VideoDownloadRecord) {
        scope.launch {
            val shouldRemoveRecord = deleteDownloadDocumentAndShouldRemoveRecord(
                context = context,
                uri = Uri.parse(record.localUri),
                diagnosticName = "delete_video_download_file",
            )
            if (shouldRemoveRecord) {
                container.videoDownloadStore.removeRecord(record)
                callbacks.setError(null)
                callbacks.setActionMessage("已删除 ${record.fileName}")
            } else {
                callbacks.setError("无法删除 ${record.fileName}，下载记录已保留")
            }
        }
    }

    fun removeVideoRecord(record: VideoDownloadRecord) {
        scope.launch {
            container.videoDownloadStore.removeRecord(record)
            callbacks.setActionMessage("已从列表移除 ${record.fileName}")
        }
    }

    private fun requireDataFolder(errorMessage: String): String? {
        val folderUri = dataFolderUri?.takeIf { it.isNotBlank() }
        if (folderUri == null) {
            callbacks.setError(errorMessage)
            callbacks.setActionMessage(null)
        }
        return folderUri
    }

    private fun clearMessages() {
        callbacks.setError(null)
        callbacks.setActionMessage(null)
    }

    private fun currentWebDavAccountId(): String =
        webDavViewModel.activeAccountId() ?: webDavViewModel.accountId()
}
