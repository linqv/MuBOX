package org.mubox.reader

import android.content.Context
import android.net.Uri
import org.mubox.reader.core.model.history.WatchHistoryEntry
import org.mubox.reader.core.model.history.WatchHistoryMetadata
import org.mubox.reader.core.model.history.WatchMediaType
import org.mubox.reader.core.model.history.WatchSourceType
import org.mubox.reader.core.model.settings.AppSettings
import org.mubox.reader.core.model.transfer.DownloadRecord
import org.mubox.reader.core.model.library.LibraryItemWithSources
import org.mubox.reader.core.model.library.SourceType
import org.mubox.reader.feature.filedirectory.FileDirectoryBrowserItem
import org.mubox.reader.infrastructure.reader.OpenComicUseCase
import org.mubox.reader.feature.reader.localComicCacheKey
import org.mubox.reader.core.model.media.readerImageFormatCacheKey
import org.mubox.reader.core.remote.RemoteFileInfo
import org.mubox.reader.core.remote.WebDavItem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal data class AppComicActionCallbacks(
    val setError: (String?) -> Unit,
    val setActionMessage: (String?) -> Unit,
    val setWebDavOpen: (Boolean) -> Unit,
    val setReaderOpen: (Boolean) -> Unit,
    val clearSelectionIf: (predicate: (AppSelection) -> Boolean) -> Unit,
    val refreshCacheAnalysis: () -> Unit,
)

internal class AppComicActions(
    private val context: Context,
    private val scope: CoroutineScope,
    private val settings: AppSettings,
    private val container: AppContainer,
    private val viewModels: AppViewModels,
    private val webDavResolver: AppWebDavResolver,
    private val callbacks: AppComicActionCallbacks,
) {
    private val webDavViewModel = viewModels.webDav
    private val readerViewModel = viewModels.reader
    private val libraryViewModel = viewModels.library
    private val fileDirectoryViewModel = viewModels.fileDirectory

    fun openLocalDirectoryComic(item: FileDirectoryBrowserItem) {
        openDirectLocalComic(
            uri = Uri.parse(item.uri),
            fileName = item.name,
            comicKey = localComicCacheKey(
                prefix = "directory",
                stableId = item.uri,
                size = item.size,
                lastModified = item.lastModified,
            ),
            sourceLocator = item.uri,
            size = item.size,
            lastModified = item.lastModified,
            readyEvent = "open_directory_local_fd_ready",
            failureEvent = "open_directory_local_fd_failed uri=${item.uri}",
            onFailure = { error -> fileDirectoryViewModel.showError(error.message ?: "打开本地文件失败") },
        )
    }

    fun openLibraryItem(item: LibraryItemWithSources) {
        when (item.item.sourceType) {
            SourceType.LOCAL -> openLocalLibraryComic(item)
            SourceType.WEBDAV -> {
                val source = item.webDavSource
                if (source == null) {
                    callbacks.setError("缺少 WebDAV 来源")
                } else {
                    openRemoteComic(
                        accountId = source.accountId,
                        remotePath = source.remotePath,
                        size = source.size,
                        etag = source.etag,
                        lastModified = source.lastModified,
                        onOpenSucceeded = { libraryViewModel.markOpened(item.item.id) },
                    )
                }
            }
        }
    }

    fun openHistoryEntry(entry: WatchHistoryEntry) {
        if (entry.mediaType != WatchMediaType.COMIC) return
        when (entry.sourceType) {
            WatchSourceType.LOCAL -> openDirectLocalComic(
                uri = Uri.parse(entry.sourceLocator),
                fileName = entry.displayTitle,
                comicKey = entry.mediaKey,
                sourceLocator = entry.sourceLocator,
                size = entry.size,
                lastModified = entry.lastModified,
                readyEvent = "open_history_local_ready",
                failureEvent = "open_history_local_failed uri=${entry.sourceLocator}",
                onFailure = { error ->
                    callbacks.setError(error.message ?: "无法打开这条历史记录，原文件可能已移动")
                },
            )
            WatchSourceType.WEB_DAV -> {
                val accountId = entry.accountId
                if (accountId.isNullOrBlank()) {
                    callbacks.setError("这条历史记录缺少 WebDAV 账号")
                    return
                }
                openRemoteComic(
                    accountId = accountId,
                    remotePath = entry.sourceLocator,
                    size = entry.size,
                    etag = entry.etag,
                    lastModified = entry.lastModified,
                )
            }
        }
    }

    fun favoriteLocalDirectoryComic(item: FileDirectoryBrowserItem) {
        scope.launch {
            runCatching {
                container.libraryRepository.addLocalComic(
                    uri = item.uri,
                    fileName = item.name,
                    size = item.size,
                    lastModified = item.lastModified,
                )
            }.fold(
                onSuccess = {
                    fileDirectoryViewModel.showMessage("已将 ${item.name} 加入书架")
                },
                onFailure = { error ->
                    container.diagnostics.error("favorite_local_directory_comic_failed uri=${item.uri}", error)
                    fileDirectoryViewModel.showError(error.message ?: "加入书架失败")
                },
            )
        }
    }

    fun favoriteWebDavComic(item: WebDavItem) {
        val client = webDavViewModel.activeClient()
        val accountId = currentWebDavAccountId()
        if (client == null) {
            callbacks.setError("请先连接 WebDAV，再加入书架")
            callbacks.setActionMessage(null)
            return
        }
        callbacks.setError(null)
        callbacks.setActionMessage(null)
        scope.launch {
            runCatching {
                val coverPath = if (settings.appearance.libraryCoversEnabled) {
                    runCatching {
                        container.coverExtractor.extractFirstPageCover(
                            client = client,
                            accountId = accountId,
                            remotePath = item.path,
                            avifImagesEnabled = effectiveAvifImagesEnabled(settings.reader.avifImagesEnabled),
                            knownInfo = item.toKnownRemoteFileInfo(),
                        )
                    }.onFailure { error ->
                        container.diagnostics.error("extract_webdav_cover_failed path=${item.path}", error)
                    }.getOrNull()
                } else {
                    null
                }
                container.libraryRepository.addWebDavComic(
                    accountId = accountId,
                    remotePath = item.path,
                    fileName = item.name,
                    size = item.size,
                    etag = item.etag,
                    lastModified = item.lastModified,
                    coverPath = coverPath,
                )
            }.fold(
                onSuccess = {
                    callbacks.refreshCacheAnalysis()
                    val message = "已将 ${item.name} 加入书架"
                    callbacks.setActionMessage(message)
                    libraryViewModel.showMessage(message)
                    fileDirectoryViewModel.showMessage(message)
                },
                onFailure = { error ->
                    val message = error.message ?: "添加 WebDAV 漫画失败"
                    callbacks.setError(message)
                    container.diagnostics.error("add_webdav_library_failed path=${item.path}", error)
                    libraryViewModel.showError(message)
                    fileDirectoryViewModel.showError(message)
                },
            )
        }
    }

    fun removeLibraryItem(item: LibraryItemWithSources) {
        scope.launch {
            runCatching {
                container.libraryRepository.removeComic(item.item.id)
            }.fold(
                onSuccess = {
                    callbacks.clearSelectionIf { it is AppSelection.LibraryItem }
                    libraryViewModel.showMessage("已将 ${item.item.displayName} 移出书架")
                },
                onFailure = { error ->
                    libraryViewModel.showError(error.message ?: "移出书架失败")
                },
            )
        }
    }

    fun refreshLibraryCover(item: LibraryItemWithSources) {
        val source = item.webDavSource ?: run {
            libraryViewModel.showError("本地漫画暂不支持重新获取封面")
            return
        }
        scope.launch {
            runCatching {
                val client = webDavResolver.clientFor(source.accountId)
                    ?: error("请先连接 ${source.accountId}，再重新获取封面")
                container.coverExtractor.extractFirstPageCover(
                    client = client,
                    accountId = source.accountId,
                    remotePath = source.remotePath,
                    avifImagesEnabled = effectiveAvifImagesEnabled(settings.reader.avifImagesEnabled),
                    knownInfo = source.size?.let { knownSize ->
                        RemoteFileInfo(
                            path = source.remotePath,
                            size = knownSize,
                            etag = source.etag,
                            lastModified = source.lastModified,
                            supportsRange = true,
                        )
                    },
                )
            }.fold(
                onSuccess = { coverPath ->
                    container.libraryRepository.updateCoverPath(item.item.id, coverPath)
                    callbacks.clearSelectionIf { it is AppSelection.LibraryItem }
                    callbacks.refreshCacheAnalysis()
                    libraryViewModel.showMessage("已重新获取 ${item.item.displayName} 的封面")
                },
                onFailure = { error ->
                    container.diagnostics.error("refresh_library_cover_failed id=${item.item.id}", error)
                    libraryViewModel.showError(error.message ?: "重新获取封面失败")
                },
            )
        }
    }

    fun openRemoteComic(
        accountId: String,
        remotePath: String,
        size: Long?,
        etag: String?,
        lastModified: Long?,
        onOpenSucceeded: (() -> Unit)? = null,
    ) {
        val client = webDavViewModel.activeClient()
        val activeAccountId = webDavViewModel.activeAccountId()
        if (client == null || activeAccountId != accountId) {
            scope.launch {
                val savedAccount = container.webDavAccountStore.loadAccount(accountId)
                if (savedAccount == null) {
                    callbacks.setError("请先连接 $accountId，再打开这个 WebDAV 漫画")
                    callbacks.setWebDavOpen(true)
                    return@launch
                }
                callbacks.setError(null)
                webDavViewModel.connectToSavedSource(
                    baseUrl = savedAccount.baseUrl,
                    username = savedAccount.username,
                    password = savedAccount.password,
                    path = "/",
                )
                openRemoteComic(
                    accountId = accountId,
                    remotePath = remotePath,
                    size = size,
                    etag = etag,
                    lastModified = lastModified,
                    onOpenSucceeded = onOpenSucceeded,
                )
            }
            return
        }
        callbacks.setError(null)
        callbacks.setActionMessage(null)
        callbacks.setReaderOpen(true)
        val avifImagesEnabled = effectiveAvifImagesEnabled(settings.reader.avifImagesEnabled)
        readerViewModel.openRemote(
            cacheDir = context.cacheDir,
            historyMetadata = comicHistoryMetadata(
                mediaKey = "",
                title = remotePath.substringAfterLast('/').ifBlank { remotePath },
                sourceType = WatchSourceType.WEB_DAV,
                sourceLocator = remotePath,
                accountId = accountId,
                size = size,
                etag = etag,
                lastModified = lastModified,
            ),
        ) {
            val result = OpenComicUseCase(
                accountId = accountId,
                cache = container.remoteCache,
                progressStore = container.progressStore,
                diagnostics = container.diagnostics,
                avifImagesEnabled = avifImagesEnabled,
                webDavPrefetchPageCount = settings.storage.webDavPrefetchPageCount,
            ).open(
                client = client,
                remotePath = remotePath,
                knownInfo = size?.let { knownSize ->
                    RemoteFileInfo(
                        path = remotePath,
                        size = knownSize,
                        etag = etag,
                        lastModified = lastModified,
                        supportsRange = true,
                    )
                },
            )
            onOpenSucceeded?.invoke()
            result
        }
    }

    fun openDownloadRecordComic(record: DownloadRecord) {
        val localUri = record.localUri
        if (!localUri.isNullOrBlank()) {
            openDirectLocalComic(
                uri = Uri.parse(localUri),
                fileName = record.fileName,
                comicKey = localComicCacheKey(
                    prefix = "download",
                    stableId = localUri,
                    size = record.sizeBytes,
                    lastModified = record.downloadedAtMillis,
                ),
                sourceLocator = localUri,
                size = record.sizeBytes,
                lastModified = record.downloadedAtMillis,
                readyEvent = "open_download_local_ready",
                failureEvent = "open_download_local_failed uri=$localUri",
                onFailure = { error ->
                    callbacks.setError(error.message ?: "无法打开这条下载记录，文件可能已被删除")
                },
            )
            return
        }

        val accountId = record.accountId
            ?: webDavViewModel.activeAccountId()
            ?: webDavViewModel.accountId().takeIf { it.substringBefore("|").isNotBlank() }
        if (accountId.isNullOrBlank()) {
            callbacks.setError("这条下载记录缺少 WebDAV 账号，也没有本地文件位置")
            return
        }
        openRemoteComic(accountId, record.remotePath, record.sizeBytes, null, null)
    }

    private fun openLocalLibraryComic(item: LibraryItemWithSources) {
        val source = item.localSource ?: run {
            callbacks.setError("缺少本地来源")
            return
        }
        openDirectLocalComic(
            uri = Uri.parse(source.uri),
            fileName = source.fileName,
            comicKey = localComicCacheKey(
                prefix = "library",
                stableId = "${item.item.id}:${source.uri}",
                size = source.size,
                lastModified = source.lastModified,
            ),
            sourceLocator = source.uri,
            size = source.size,
            lastModified = source.lastModified,
            readyEvent = "open_library_local_fd_ready",
            failureEvent = "open_library_local_fd_failed",
            onOpened = { libraryViewModel.markOpened(item.item.id) },
            onFailure = { error -> callbacks.setError(error.message ?: "打开本地文件失败") },
        )
    }

    private fun openDirectLocalComic(
        uri: Uri,
        fileName: String,
        comicKey: String,
        sourceLocator: String,
        size: Long? = null,
        lastModified: Long? = null,
        readyEvent: String,
        failureEvent: String,
        onOpened: () -> Unit = {},
        onFailure: (Throwable) -> Unit,
    ) {
        val avifImagesEnabled = effectiveAvifImagesEnabled(settings.reader.avifImagesEnabled)
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    container.localComicOpener.open(uri, fileName, avifImagesEnabled = avifImagesEnabled)
                }
            }.fold(
                onSuccess = { session ->
                    callbacks.setError(null)
                    onOpened()
                    readerViewModel.openExistingSession(
                        openedSession = session,
                        cacheDir = context.cacheDir,
                        initialPage = container.progressStore.loadPage(comicKey),
                        comicKey = comicKey,
                        pageCacheKey = readerImageFormatCacheKey(comicKey, avifImagesEnabled),
                        historyMetadata = comicHistoryMetadata(
                            mediaKey = comicKey,
                            title = fileName,
                            sourceType = WatchSourceType.LOCAL,
                            sourceLocator = sourceLocator,
                            size = size,
                            lastModified = lastModified,
                        ),
                    )
                    callbacks.setReaderOpen(true)
                },
                onFailure = { error ->
                    container.diagnostics.error(failureEvent, error)
                    onFailure(error)
                },
            )
        }
    }

    private fun currentWebDavAccountId(): String =
        webDavViewModel.activeAccountId() ?: webDavViewModel.accountId()
}

internal fun comicHistoryMetadata(
    mediaKey: String,
    title: String,
    sourceType: WatchSourceType,
    sourceLocator: String,
    accountId: String? = null,
    size: Long? = null,
    etag: String? = null,
    lastModified: Long? = null,
): WatchHistoryMetadata =
    WatchHistoryMetadata(
        mediaKey = mediaKey,
        mediaType = WatchMediaType.COMIC,
        title = title,
        sourceType = sourceType,
        sourceLocator = sourceLocator,
        accountId = accountId,
        size = size,
        etag = etag,
        lastModified = lastModified,
    )

private fun WebDavItem.toKnownRemoteFileInfo(): RemoteFileInfo? =
    size?.let { knownSize ->
        RemoteFileInfo(
            path = path,
            size = knownSize,
            etag = etag,
            lastModified = lastModified,
            supportsRange = true,
        )
    }
