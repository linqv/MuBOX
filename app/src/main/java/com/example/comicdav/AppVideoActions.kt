package com.example.comicdav

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.example.comicdav.core.model.history.WatchHistoryEntry
import com.example.comicdav.core.model.history.WatchMediaType
import com.example.comicdav.core.model.history.WatchSourceType
import com.example.comicdav.core.model.settings.AppSettings
import com.example.comicdav.core.model.settings.VideoProxySettings
import com.example.comicdav.data.VideoDownloadRecord
import com.example.comicdav.data.library.LibraryItemWithSources
import com.example.comicdav.data.videolibrary.VideoLibraryItemWithSources
import com.example.comicdav.data.videolibrary.VideoSourceType
import com.example.comicdav.feature.directorylisting.MAX_DIRECTORY_VIDEO_THUMBNAILS
import com.example.comicdav.feature.filedirectory.FileDirectoryBrowserItem
import com.example.comicdav.feature.filedirectory.fileDirectoryBrowserVideoThumbnailVersion
import com.example.comicdav.feature.filedirectory.fileDirectoryVideoThumbnailVersion
import com.example.comicdav.feature.home.historyEntriesNeedingThumbnails
import com.example.comicdav.feature.home.historyThumbnailFile
import com.example.comicdav.feature.home.historyThumbnailStableKey
import com.example.comicdav.feature.reader.ReaderDiagnosticLog
import com.example.comicdav.feature.webdav.hasReliableWebDavVideoThumbnailVersion
import com.example.comicdav.feature.webdav.mediaKind
import com.example.comicdav.feature.webdav.webDavBrowserVideoThumbnailVersion
import com.example.comicdav.core.remote.WebDavItem
import com.example.comicdav.core.remote.RemoteFileInfo
import com.example.comicdav.core.model.media.LocalVideoOpenRequest
import com.example.comicdav.core.model.media.MediaKind
import com.example.comicdav.core.model.media.VideoSubtitleOpenRequest
import com.example.comicdav.core.model.media.WebDavSubtitleOpenRequest
import com.example.comicdav.core.model.media.WebDavVideoOpenRequest
import com.example.comicdav.core.model.media.findSidecarSubtitles
import com.example.comicdav.core.model.media.mimeTypeForMediaFileName
import com.example.comicdav.video.player.VideoPlayerActivity
import com.example.comicdav.video.player.buildLocalDirectoryEpisodeQueue
import com.example.comicdav.video.player.buildWebDavDirectoryEpisodeQueue
import com.example.comicdav.video.player.localVideoEpisodeRequest
import com.example.comicdav.video.player.webDavVideoEpisodeRequest
import com.example.comicdav.video.proxy.VideoProxyManager
import com.example.comicdav.video.proxy.startWebDavVideoPlayback
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

internal data class AppVideoActionCallbacks(
    val launchPlayer: (Intent) -> Unit,
    val setError: (String?) -> Unit,
    val setActionMessage: (String?) -> Unit,
    val clearSelectionIf: (predicate: (AppSelection) -> Boolean) -> Unit,
)

internal class BrowserThumbnailRequestCoordinator(
    private val scope: CoroutineScope,
    maxParallelism: Int = 2,
    private val orphanGracePeriodMillis: Long = 500L,
) {
    private class Request(
        val deferred: Deferred<String?>,
        var waiterCount: Int,
        var orphanCancellation: Job? = null,
    )

    private val requestLock = Any()
    private val requestsInFlight = mutableMapOf<String, Request>()
    private val extractionSemaphore = Semaphore(maxParallelism)

    init {
        require(maxParallelism > 0)
        require(orphanGracePeriodMillis >= 0L)
    }

    suspend fun request(
        stableKey: String,
        extract: suspend () -> String?,
    ): String? {
        var shouldStartRequest = false
        val request = synchronized(requestLock) {
            requestsInFlight[stableKey]?.also { existingRequest ->
                existingRequest.waiterCount += 1
                existingRequest.orphanCancellation?.cancel()
                existingRequest.orphanCancellation = null
            } ?: run {
                val deferred = scope.async(start = CoroutineStart.LAZY) {
                    try {
                        extractionSemaphore.withPermit { extract() }
                    } catch (error: CancellationException) {
                        throw error
                    } catch (_: Throwable) {
                        null
                    }
                }
                Request(
                    deferred = deferred,
                    waiterCount = 1,
                ).also { createdRequest ->
                    requestsInFlight[stableKey] = createdRequest
                    shouldStartRequest = true
                }
            }
        }
        if (shouldStartRequest) {
            request.deferred.start()
        }
        return try {
            request.deferred.await()
        } finally {
            releaseWaiter(stableKey, request)
        }
    }

    private fun releaseWaiter(stableKey: String, request: Request) {
        synchronized(requestLock) {
            if (requestsInFlight[stableKey] !== request) return
            request.waiterCount = (request.waiterCount - 1).coerceAtLeast(0)
            if (request.waiterCount > 0) return
            if (request.deferred.isCompleted) {
                requestsInFlight.remove(stableKey)
                return
            }
            request.orphanCancellation = scope.launch {
                delay(orphanGracePeriodMillis)
                val orphanedRequest = synchronized(requestLock) {
                    requestsInFlight[stableKey]
                        ?.takeIf { it === request && it.waiterCount == 0 }
                        ?.also { requestsInFlight.remove(stableKey) }
                }
                orphanedRequest?.deferred?.cancel()
            }
        }
    }
}

internal class AppVideoActions(
    private val context: Context,
    private val scope: CoroutineScope,
    private val settings: AppSettings,
    private val container: AppContainer,
    private val viewModels: AppViewModels,
    private val webDavResolver: AppWebDavResolver,
    private val callbacks: AppVideoActionCallbacks,
) {
    private val webDavViewModel = viewModels.webDav
    private val fileDirectoryViewModel = viewModels.fileDirectory
    private val videoLibraryViewModel = viewModels.videoLibrary
    private val failedBrowserThumbnailRequestRevisions =
        object : LinkedHashMap<String, Long>(MAX_DIRECTORY_VIDEO_THUMBNAILS, 0.75f, true) {
            override fun removeEldestEntry(
                eldest: MutableMap.MutableEntry<String, Long>?,
            ): Boolean = size > MAX_DIRECTORY_VIDEO_THUMBNAILS
        }
    private val browserThumbnailRequests = BrowserThumbnailRequestCoordinator(scope)

    fun openLocalDirectoryVideo(item: FileDirectoryBrowserItem) {
        val episodeQueue = buildLocalDirectoryEpisodeQueue(
            entries = fileDirectoryViewModel.playbackDirectoryEntries(),
            currentItem = item,
        )
        val request = episodeQueue?.currentEpisode?.localRequest
            ?: localVideoEpisodeRequest(item, fileDirectoryViewModel.uiState.entries)
        callbacks.setError(null)
        callbacks.launchPlayer(
            VideoPlayerActivity.localIntent(
                context = context,
                request = request,
                options = settings.toVideoPlayerOptions(),
                episodeQueue = episodeQueue,
            ),
        )
    }

    suspend fun requestLocalBrowserVideoThumbnail(item: FileDirectoryBrowserItem) {
        if (!settings.gridVideoThumbnailsEnabled || item.mediaKind != MediaKind.Video) return
        val requestRevision = fileDirectoryViewModel.uiState.thumbnailRequestRevision
        val stableKey = fileDirectoryBrowserVideoThumbnailVersion(
            item = item,
            requestRevision = requestRevision,
        )
        if (
            shouldSkipBrowserThumbnailRequest(
                failedRevision = failedBrowserThumbnailRequestRevisions[stableKey],
                requestRevision = requestRevision,
            )
        ) {
            return
        }
        val thumbnailPath = browserThumbnailRequests.request(stableKey) {
            extractLocalVideoThumbnail(
                uri = item.uri,
                size = item.size,
                lastModified = item.lastModified,
                extractor = container.browserVideoThumbnailExtractor,
                stableKey = stableKey,
            )
        }
        if (thumbnailPath == null) {
            failedBrowserThumbnailRequestRevisions[stableKey] = requestRevision
            return
        }
        failedBrowserThumbnailRequestRevisions -= stableKey
        fileDirectoryViewModel.onVideoThumbnailExtracted(
            uri = item.uri,
            version = stableKey,
            thumbnailPath = thumbnailPath,
        )
    }

    suspend fun requestWebDavBrowserVideoThumbnail(item: WebDavItem) {
        if (!settings.gridVideoThumbnailsEnabled || item.mediaKind != MediaKind.Video) return
        val accountId = currentWebDavAccountId()
        if (accountId.substringBefore("|").isBlank()) return
        val request = webDavVideoRequestForItem(item)
        val requestRevision = webDavViewModel.uiState.thumbnailRequestRevision
        val version = webDavBrowserVideoThumbnailVersion(
            item = item,
            requestRevision = requestRevision,
        )
        val stableKey = webDavBrowserVideoThumbnailStableKey(
            request = request,
            requestRevision = requestRevision,
        )
        if (
            shouldSkipBrowserThumbnailRequest(
                failedRevision = failedBrowserThumbnailRequestRevisions[stableKey],
                requestRevision = requestRevision,
            )
        ) {
            return
        }
        val thumbnailPath = browserThumbnailRequests.request(stableKey) {
            extractWebDavVideoThumbnail(
                request = request,
                extractor = container.browserVideoThumbnailExtractor,
                stableKey = stableKey,
            )
        }
        if (thumbnailPath == null) {
            failedBrowserThumbnailRequestRevisions[stableKey] = requestRevision
            return
        }
        failedBrowserThumbnailRequestRevisions -= stableKey
        if (currentWebDavAccountId() == accountId) {
            webDavViewModel.onVideoThumbnailExtracted(
                path = item.path,
                version = version,
                thumbnailPath = thumbnailPath,
            )
        }
    }

    fun openHistoryEntry(entry: WatchHistoryEntry) {
        if (entry.mediaType != WatchMediaType.VIDEO) return
        when (entry.sourceType) {
            WatchSourceType.LOCAL -> {
                callbacks.setError(null)
                callbacks.launchPlayer(
                    VideoPlayerActivity.localIntent(
                        context = context,
                        request = LocalVideoOpenRequest(
                            uri = entry.sourceLocator,
                            displayName = entry.displayTitle,
                            size = entry.size,
                            lastModified = entry.lastModified,
                        ),
                        options = settings.toVideoPlayerOptions(),
                    ),
                )
            }
            WatchSourceType.WEB_DAV -> {
                val accountId = entry.accountId
                if (accountId.isNullOrBlank()) {
                    callbacks.setError("这条历史记录缺少 WebDAV 账号")
                    return
                }
                val request = WebDavVideoOpenRequest(
                    accountId = accountId,
                    remotePath = entry.sourceLocator,
                    displayName = entry.displayTitle,
                    size = entry.size,
                    etag = entry.etag,
                    lastModified = entry.lastModified,
                    mimeType = mimeTypeForMediaFileName(entry.displayTitle),
                )
                scope.launch {
                    runCatching {
                        val clientFactory = webDavResolver.clientFactoryForPlayback(accountId)
                            ?: error("缺少 WebDAV 账号，请重新连接后再打开视频")
                        startWebDavVideoPlayback(
                            request = request,
                            clientFactory = clientFactory,
                            proxySettings = settings.toVideoProxySettings(),
                        ) { session ->
                            callbacks.launchPlayer(
                                VideoPlayerActivity.webDavIntent(
                                    context = context,
                                    request = request,
                                    uri = session.url,
                                    subtitleUrls = session.subtitleUrls,
                                    streamIds = session.streamIds,
                                    options = settings.toVideoPlayerOptions(),
                                ),
                            )
                        }
                    }.onFailure { error ->
                        callbacks.setError(error.message ?: "打开历史视频失败")
                    }
                }
            }
        }
    }

    fun favoriteLocalDirectoryVideo(item: FileDirectoryBrowserItem) {
        scope.launch {
            runCatching {
                val thumbnailPath = if (settings.videoLibraryThumbnailsEnabled) {
                    runCatching {
                        extractLocalVideoThumbnail(
                            uri = item.uri,
                            size = item.size,
                            lastModified = item.lastModified,
                        )
                    }.onFailure { error ->
                        ReaderDiagnosticLog.error("extract_local_video_thumbnail_failed uri=${item.uri}", error)
                    }.getOrNull()
                } else {
                    null
                }
                container.videoLibraryRepository.addLocalVideo(
                    uri = item.uri,
                    fileName = item.name,
                    size = item.size,
                    lastModified = item.lastModified,
                    thumbnailPath = thumbnailPath,
                )
            }.fold(
                onSuccess = {
                    callbacks.clearSelectionIf { it is AppSelection.DirectoryVideo }
                    videoLibraryViewModel.showMessage("已将 ${item.name} 加入影视库")
                    fileDirectoryViewModel.showMessage("已将 ${item.name} 加入影视库")
                },
                onFailure = { error ->
                    ReaderDiagnosticLog.error("favorite_local_directory_video_failed uri=${item.uri}", error)
                    videoLibraryViewModel.showError(error.message ?: "加入影视库失败")
                    fileDirectoryViewModel.showError(error.message ?: "加入影视库失败")
                },
            )
        }
    }

    fun favoriteWebDavVideo(item: WebDavItem) {
        val accountId = currentWebDavAccountId()
        if (accountId.substringBefore("|").isBlank()) {
            callbacks.setActionMessage(null)
            callbacks.setError("请先连接 WebDAV，再加入影视库")
            return
        }
        callbacks.setError(null)
        callbacks.setActionMessage(null)
        scope.launch {
            runCatching {
                val request = webDavVideoRequestForItem(item)
                val thumbnailPath = if (settings.videoLibraryThumbnailsEnabled) {
                    runCatching {
                        extractWebDavVideoThumbnail(request)
                    }.onFailure { error ->
                        ReaderDiagnosticLog.error("extract_webdav_video_thumbnail_failed path=${item.path}", error)
                    }.getOrNull()
                } else {
                    null
                }
                container.videoLibraryRepository.addWebDavVideo(
                    accountId = accountId,
                    remotePath = item.path,
                    fileName = item.name,
                    size = item.size,
                    etag = item.etag,
                    lastModified = item.lastModified,
                    thumbnailPath = thumbnailPath,
                )
            }.fold(
                onSuccess = {
                    callbacks.clearSelectionIf { it is AppSelection.WebDavFile }
                    callbacks.setActionMessage("已将 ${item.name} 加入影视库")
                    videoLibraryViewModel.showMessage("已将 ${item.name} 加入影视库")
                    fileDirectoryViewModel.showMessage("已将 ${item.name} 加入影视库")
                },
                onFailure = { error ->
                    callbacks.setError(error.message ?: "添加 WebDAV 视频失败")
                    ReaderDiagnosticLog.error("add_webdav_video_library_failed path=${item.path}", error)
                    videoLibraryViewModel.showError(error.message ?: "添加 WebDAV 视频失败")
                    fileDirectoryViewModel.showError(error.message ?: "添加 WebDAV 视频失败")
                },
            )
        }
    }

    fun openWebDavVideo(item: WebDavItem) {
        val accountId = currentWebDavAccountId()
        val episodeQueue = buildWebDavDirectoryEpisodeQueue(
            accountId = accountId,
            items = webDavViewModel.playbackDirectoryItems(),
            currentItem = item,
        )
        val request = episodeQueue?.currentEpisode?.webDavRequest
            ?: webDavVideoEpisodeRequest(accountId, item, webDavViewModel.uiState.items)
        callbacks.setError(null)
        callbacks.setActionMessage("已进入内部视频打开流程：${item.name}")
        scope.launch {
            runCatching {
                val clientFactory = webDavResolver.clientFactoryForPlayback(accountId)
                    ?: error("缺少 WebDAV 账号，请重新连接后再打开视频")
                startWebDavVideoPlayback(
                    request = request,
                    clientFactory = clientFactory,
                    proxySettings = settings.toVideoProxySettings(),
                ) { session ->
                    callbacks.launchPlayer(
                        VideoPlayerActivity.webDavIntent(
                            context = context,
                            request = request,
                            uri = session.url,
                            subtitleUrls = session.subtitleUrls,
                            streamIds = session.streamIds,
                            options = settings.toVideoPlayerOptions(),
                            episodeQueue = episodeQueue,
                        ),
                    )
                }
            }.onFailure { error ->
                callbacks.setError(error.message ?: "打开视频失败")
                callbacks.setActionMessage(null)
            }
        }
    }

    fun openVideoLibraryItem(item: VideoLibraryItemWithSources) {
        when (item.item.sourceType) {
            VideoSourceType.LOCAL -> openLocalVideoLibraryItem(item)
            VideoSourceType.WEBDAV -> openWebDavVideoLibraryItem(item)
        }
    }

    fun removeVideoLibraryItem(item: VideoLibraryItemWithSources) {
        scope.launch {
            runCatching {
                item.item.thumbnailPath?.let { path ->
                    withContext(Dispatchers.IO) {
                        File(path).takeIf { it.isFile }?.delete()
                    }
                }
                container.videoLibraryRepository.removeVideo(item.item.id)
            }.fold(
                onSuccess = {
                    callbacks.clearSelectionIf { it is AppSelection.VideoLibraryItem }
                    videoLibraryViewModel.showMessage("已将 ${item.item.displayName} 移出影视库")
                },
                onFailure = { error ->
                    videoLibraryViewModel.showError(error.message ?: "移出影视库失败")
                },
            )
        }
    }

    fun refreshVideoLibraryThumbnail(item: VideoLibraryItemWithSources) {
        scope.launch {
            runCatching {
                extractVideoLibraryThumbnail(item, forceRefresh = true)
            }.fold(
                onSuccess = { thumbnailPath ->
                    container.videoLibraryRepository.updateThumbnailPath(item.item.id, thumbnailPath)
                    videoLibraryViewModel.onVideoThumbnailExtracted(
                        videoLibraryItemId = item.item.id,
                        thumbnailPath = thumbnailPath,
                    )
                    onVideoLibraryThumbnailRefreshed(
                        item = item,
                        thumbnailPath = thumbnailPath,
                    )
                    callbacks.clearSelectionIf { it is AppSelection.VideoLibraryItem }
                    videoLibraryViewModel.showMessage("已重新提取 ${item.item.displayName} 的缩略图")
                },
                onFailure = { error ->
                    ReaderDiagnosticLog.error("refresh_video_thumbnail_failed id=${item.item.id}", error)
                    videoLibraryViewModel.showError(error.message ?: "重新提取缩略图失败")
                },
            )
        }
    }

    fun extractMissingThumbnails(
        videoLibraryItems: List<VideoLibraryItemWithSources>,
        history: List<WatchHistoryEntry>,
        libraryItems: List<LibraryItemWithSources>,
    ) {
        if (!settings.videoLibraryThumbnailsEnabled && !settings.libraryCoversEnabled) {
            videoLibraryViewModel.showThumbnailExtractionResult(
                message = "请先在设置中开启封面或缩略图",
                isError = true,
            )
            return
        }
        if (videoLibraryViewModel.uiState.isExtractingThumbnails) return

        val videoTargets = if (settings.videoLibraryThumbnailsEnabled) {
            videoLibraryItemsNeedingThumbnails(videoLibraryItems)
        } else {
            emptyList()
        }
        val scheduledVideoLocators = videoTargets.mapNotNullTo(mutableSetOf()) { item ->
            item.localSource?.uri ?: item.webDavSource?.remotePath
        }
        val historyTargets = historyEntriesNeedingThumbnails(
            history = history,
            comics = libraryItems,
            videos = videoLibraryItems,
            cacheDir = context.cacheDir,
        ).filter { entry ->
            val typeEnabled = when (entry.mediaType) {
                WatchMediaType.COMIC -> settings.libraryCoversEnabled
                WatchMediaType.VIDEO -> settings.videoLibraryThumbnailsEnabled
            }
            typeEnabled &&
                !(entry.mediaType == WatchMediaType.VIDEO && entry.sourceLocator in scheduledVideoLocators)
        }
        val targetCount = videoTargets.size + historyTargets.size
        if (targetCount == 0) {
            videoLibraryViewModel.showThumbnailExtractionResult(
                if (videoLibraryItems.isEmpty() && history.isEmpty()) {
                    "没有可提取缩略图的媒体"
                } else {
                    "缩略图已完整"
                },
            )
            return
        }

        videoLibraryViewModel.setThumbnailExtractionInProgress(true)
        scope.launch {
            var succeeded = 0
            var failed = 0
            try {
                videoTargets.forEach { item ->
                    try {
                        val thumbnailPath = extractVideoLibraryThumbnail(item)
                        container.videoLibraryRepository.updateThumbnailPath(item.item.id, thumbnailPath)
                        videoLibraryViewModel.onVideoThumbnailExtracted(
                            videoLibraryItemId = item.item.id,
                            thumbnailPath = thumbnailPath,
                        )
                        succeeded += 1
                    } catch (error: CancellationException) {
                        throw error
                    } catch (error: Throwable) {
                        failed += 1
                        ReaderDiagnosticLog.error(
                            "batch_extract_video_thumbnail_failed id=${item.item.id}",
                            error,
                        )
                    }
                }
                historyTargets.forEach { entry ->
                    try {
                        extractHistoryThumbnail(entry)
                        videoLibraryViewModel.onHistoryThumbnailExtracted(entry.mediaKey)
                        succeeded += 1
                    } catch (error: CancellationException) {
                        throw error
                    } catch (error: Throwable) {
                        failed += 1
                        ReaderDiagnosticLog.error(
                            "batch_extract_history_thumbnail_failed key=${entry.mediaKey}",
                            error,
                        )
                    }
                }
            } finally {
                videoLibraryViewModel.setThumbnailExtractionInProgress(false)
            }

            when {
                failed == 0 ->
                    videoLibraryViewModel.showThumbnailExtractionResult(
                        "已提取 $succeeded 个缩略图",
                    )
                succeeded == 0 ->
                    videoLibraryViewModel.showThumbnailExtractionResult(
                        message = "$failed 个缩略图提取失败",
                        isError = true,
                    )
                else ->
                    videoLibraryViewModel.showThumbnailExtractionResult(
                        message = "已提取 $succeeded 个缩略图，$failed 个提取失败",
                        isError = true,
                    )
            }
        }
    }

    fun deleteVideoLibraryThumbnail(item: VideoLibraryItemWithSources) {
        scope.launch {
            runCatching {
                item.item.thumbnailPath?.let { path ->
                    withContext(Dispatchers.IO) {
                        File(path).takeIf { it.isFile }?.delete()
                    }
                }
                container.videoLibraryRepository.updateThumbnailPath(item.item.id, null)
            }.fold(
                onSuccess = {
                    callbacks.clearSelectionIf { it is AppSelection.VideoLibraryItem }
                    videoLibraryViewModel.showMessage("已删除 ${item.item.displayName} 的缩略图")
                },
                onFailure = { error ->
                    videoLibraryViewModel.showError(error.message ?: "删除缩略图失败")
                },
            )
        }
    }

    fun playVideoDownloadRecord(record: VideoDownloadRecord) {
        val request = LocalVideoOpenRequest(
            uri = record.localUri,
            displayName = record.fileName,
            size = record.sizeBytes.takeIf { it > 0L },
            lastModified = record.downloadedAtMillis,
        )
        callbacks.setError(null)
        runCatching {
            callbacks.launchPlayer(
                VideoPlayerActivity.localIntent(
                    context = context,
                    request = request,
                    options = settings.toVideoPlayerOptions(),
                ),
            )
        }.onFailure { error ->
            ReaderDiagnosticLog.error("play_video_download_failed uri=${record.localUri}", error)
            callbacks.setError(error.message ?: "无法播放该视频，文件可能已被删除")
        }
    }

    private fun openLocalVideoLibraryItem(item: VideoLibraryItemWithSources) {
        val source = item.localSource ?: run {
            videoLibraryViewModel.showError("缺少本地视频来源")
            return
        }
        scope.launch {
            val request = LocalVideoOpenRequest(
                uri = source.uri,
                displayName = source.fileName,
                size = source.size,
                lastModified = source.lastModified,
                subtitles = localVideoLibrarySubtitles(
                    videoUri = source.uri,
                    videoFileName = source.fileName,
                ),
            )
            videoLibraryViewModel.markOpened(item.item.id)
            callbacks.launchPlayer(
                VideoPlayerActivity.localIntent(
                    context = context,
                    request = request,
                    options = settings.toVideoPlayerOptions(),
                ),
            )
        }
    }

    private fun openWebDavVideoLibraryItem(item: VideoLibraryItemWithSources) {
        val source = item.webDavSource ?: run {
            videoLibraryViewModel.showError("缺少 WebDAV 视频来源")
            return
        }
        val request = WebDavVideoOpenRequest(
            accountId = source.accountId,
            remotePath = source.remotePath,
            displayName = source.fileName,
            size = source.size,
            etag = source.etag,
            lastModified = source.lastModified,
            mimeType = mimeTypeForMediaFileName(source.fileName),
        )
        scope.launch {
            runCatching {
                val subtitles = webDavVideoLibrarySubtitles(
                    accountId = source.accountId,
                    remotePath = source.remotePath,
                    videoFileName = source.fileName,
                )
                val playbackRequest = request.copy(subtitles = subtitles)
                val clientFactory = webDavResolver.clientFactoryForPlayback(source.accountId)
                    ?: error("缺少 WebDAV 账号，请重新连接后再打开视频")
                startWebDavVideoPlayback(
                    request = playbackRequest,
                    clientFactory = clientFactory,
                    proxySettings = settings.toVideoProxySettings(),
                ) { session ->
                    videoLibraryViewModel.markOpened(item.item.id)
                    callbacks.launchPlayer(
                        VideoPlayerActivity.webDavIntent(
                            context = context,
                            request = playbackRequest,
                            uri = session.url,
                            subtitleUrls = session.subtitleUrls,
                            streamIds = session.streamIds,
                            options = settings.toVideoPlayerOptions(),
                        ),
                    )
                }
            }.onFailure { error ->
                videoLibraryViewModel.showError(error.message ?: "打开视频失败")
            }
        }
    }

    private suspend fun extractLocalVideoThumbnail(
        uri: String,
        size: Long?,
        lastModified: Long?,
        forceRefresh: Boolean = false,
        extractor: com.example.comicdav.feature.videolibrary.VideoThumbnailExtractor =
            container.videoThumbnailExtractor,
        stableKey: String = fileDirectoryVideoThumbnailVersion(
            uri = uri,
            size = size,
            lastModified = lastModified,
        ),
    ): String? =
        extractor.extractFromContentUri(
            context = context,
            uri = Uri.parse(uri),
            stableKey = stableKey,
            forceRefresh = forceRefresh,
        )

    private fun onVideoLibraryThumbnailRefreshed(
        item: VideoLibraryItemWithSources,
        thumbnailPath: String,
    ) {
        when (item.item.sourceType) {
            VideoSourceType.LOCAL -> {
                val source = item.localSource ?: return
                val version = fileDirectoryBrowserVideoThumbnailVersion(
                    uri = source.uri,
                    size = source.size,
                    lastModified = source.lastModified,
                    requestRevision = fileDirectoryViewModel.uiState.thumbnailRequestRevision,
                )
                if (fileDirectoryViewModel.uiState.videoThumbnails[source.uri]?.version != version) return
                fileDirectoryViewModel.onVideoThumbnailExtracted(
                    uri = source.uri,
                    version = version,
                    thumbnailPath = thumbnailPath,
                )
            }
            VideoSourceType.WEBDAV -> {
                val source = item.webDavSource ?: return
                if (currentWebDavAccountId() != source.accountId) return
                val version = webDavBrowserVideoThumbnailVersion(
                    path = source.remotePath,
                    size = source.size,
                    etag = source.etag,
                    lastModified = source.lastModified,
                    requestRevision = webDavViewModel.uiState.thumbnailRequestRevision,
                )
                if (webDavViewModel.uiState.videoThumbnails[source.remotePath]?.version != version) return
                webDavViewModel.onVideoThumbnailExtracted(
                    path = source.remotePath,
                    version = version,
                    thumbnailPath = thumbnailPath,
                )
            }
        }
    }

    private suspend fun extractWebDavVideoThumbnail(
        request: WebDavVideoOpenRequest,
        forceRefresh: Boolean = false,
    ): String? {
        return extractWebDavVideoThumbnail(
            request = request,
            extractor = container.videoThumbnailExtractor,
            stableKey = webDavVideoThumbnailStableKey(request),
            forceRefresh = forceRefresh,
        )
    }

    private suspend fun extractWebDavVideoThumbnail(
        request: WebDavVideoOpenRequest,
        extractor: com.example.comicdav.feature.videolibrary.VideoThumbnailExtractor,
        stableKey: String,
        forceRefresh: Boolean = false,
    ): String? {
        if (!forceRefresh) {
            extractor.cachedThumbnailPath(stableKey)?.let { return it }
        }
        val clientFactory = webDavResolver.clientFactoryForPlayback(request.accountId)
            ?: error("缺少 WebDAV 账号，请重新连接后再提取缩略图")
        val session = VideoProxyManager.open(
            request = request.copy(subtitles = emptyList()),
            clientFactory = clientFactory,
            proxySettings = settings.toVideoProxySettings(),
        )
        return try {
            extractor.extractFromUrl(
                url = session.url,
                stableKey = stableKey,
                forceRefresh = forceRefresh,
            )
        } finally {
            VideoProxyManager.close(session.streamIds)
        }
    }

    private fun webDavVideoThumbnailStableKey(request: WebDavVideoOpenRequest): String =
        "webdav:${request.accountId}:${request.remotePath}:${request.size ?: -1}:" +
            "${request.etag.orEmpty()}:${request.lastModified ?: -1}"

    private fun webDavBrowserVideoThumbnailStableKey(
        request: WebDavVideoOpenRequest,
        requestRevision: Long,
    ): String {
        val stableKey = webDavVideoThumbnailStableKey(request)
        return if (hasReliableWebDavVideoThumbnailVersion(request.etag, request.lastModified)) {
            stableKey
        } else {
            "$stableKey:directory-revision:$requestRevision"
        }
    }

    private suspend fun extractVideoLibraryThumbnail(
        item: VideoLibraryItemWithSources,
        forceRefresh: Boolean = false,
    ): String =
        when (item.item.sourceType) {
            VideoSourceType.LOCAL -> {
                val source = item.localSource ?: error("缺少本地视频来源")
                extractLocalVideoThumbnail(
                    uri = source.uri,
                    size = source.size,
                    lastModified = source.lastModified,
                    forceRefresh = forceRefresh,
                ) ?: error("未能提取视频缩略图")
            }
            VideoSourceType.WEBDAV -> {
                val source = item.webDavSource ?: error("缺少 WebDAV 视频来源")
                extractWebDavVideoThumbnail(
                    request = WebDavVideoOpenRequest(
                        accountId = source.accountId,
                        remotePath = source.remotePath,
                        displayName = source.fileName,
                        size = source.size,
                        etag = source.etag,
                        lastModified = source.lastModified,
                        mimeType = mimeTypeForMediaFileName(source.fileName),
                    ),
                    forceRefresh = forceRefresh,
                ) ?: error("未能提取视频缩略图")
            }
        }

    private suspend fun extractHistoryThumbnail(entry: WatchHistoryEntry): String =
        when (entry.mediaType) {
            WatchMediaType.VIDEO -> extractHistoryVideoThumbnail(entry)
            WatchMediaType.COMIC -> extractHistoryComicThumbnail(entry)
        }

    private suspend fun extractHistoryVideoThumbnail(entry: WatchHistoryEntry): String {
        val stableKey = historyThumbnailStableKey(entry)
        return when (entry.sourceType) {
            WatchSourceType.LOCAL ->
                container.historyThumbnailExtractor.extractFromContentUri(
                    context = context,
                    uri = Uri.parse(entry.sourceLocator),
                    stableKey = stableKey,
                ) ?: error("未能提取历史视频缩略图")
            WatchSourceType.WEB_DAV -> {
                val accountId = entry.accountId ?: error("历史记录缺少 WebDAV 账号")
                extractWebDavVideoThumbnail(
                    request = WebDavVideoOpenRequest(
                        accountId = accountId,
                        remotePath = entry.sourceLocator,
                        displayName = entry.displayTitle,
                        size = entry.size,
                        etag = entry.etag,
                        lastModified = entry.lastModified,
                        mimeType = mimeTypeForMediaFileName(entry.displayTitle),
                    ),
                    extractor = container.historyThumbnailExtractor,
                    stableKey = stableKey,
                ) ?: error("未能提取历史视频缩略图")
            }
        }
    }

    private suspend fun extractHistoryComicThumbnail(entry: WatchHistoryEntry): String {
        val target = historyThumbnailFile(context.cacheDir, entry)
        return when (entry.sourceType) {
            WatchSourceType.LOCAL -> withContext(Dispatchers.IO) {
                val session = container.localComicOpener.open(
                    uri = Uri.parse(entry.sourceLocator),
                    fileName = entry.displayTitle,
                    avifImagesEnabled = effectiveAvifImagesEnabled(settings.avifImagesEnabled),
                )
                val temporary = File(target.parentFile, "${target.name}.tmp")
                try {
                    check(session.pageCount > 0) { "历史漫画没有可用页面" }
                    target.parentFile?.mkdirs()
                    temporary.delete()
                    val loaded = session.loadPageToFile(0, temporary)
                    moveHistoryThumbnailIntoPlace(loaded, target)
                } finally {
                    runCatching { session.close() }
                    temporary.delete()
                }
            }
            WatchSourceType.WEB_DAV -> {
                val accountId = entry.accountId ?: error("历史记录缺少 WebDAV 账号")
                val client = webDavResolver.clientFor(accountId)
                    ?: error("请先连接 $accountId，再提取历史漫画缩略图")
                val coverPath = container.coverExtractor.extractFirstPageCover(
                    client = client,
                    accountId = accountId,
                    remotePath = entry.sourceLocator,
                    avifImagesEnabled = effectiveAvifImagesEnabled(settings.avifImagesEnabled),
                    knownInfo = entry.size?.let { size ->
                        RemoteFileInfo(
                            path = entry.sourceLocator,
                            size = size,
                            etag = entry.etag,
                            lastModified = entry.lastModified,
                            supportsRange = true,
                        )
                    },
                ) ?: error("未能提取历史漫画缩略图")
                withContext(Dispatchers.IO) {
                    val temporary = File(target.parentFile, "${target.name}.tmp")
                    try {
                        target.parentFile?.mkdirs()
                        temporary.delete()
                        File(coverPath).copyTo(temporary, overwrite = true)
                        moveHistoryThumbnailIntoPlace(temporary, target)
                    } finally {
                        temporary.delete()
                    }
                }
            }
        }
    }

    private fun moveHistoryThumbnailIntoPlace(source: File, target: File): String {
        check(source.isFile && source.length() > 0L) { "提取的历史缩略图为空" }
        target.parentFile?.mkdirs()
        if (target.exists()) target.delete()
        if (!source.renameTo(target)) {
            source.copyTo(target, overwrite = true)
            source.delete()
        }
        return target.absolutePath
    }

    private suspend fun localVideoLibrarySubtitles(
        videoUri: String,
        videoFileName: String,
    ): List<VideoSubtitleOpenRequest> {
        val parentUri = parentDocumentUriForLocalVideo(Uri.parse(videoUri)) ?: return emptyList()
        val siblings = runCatching {
            container.localDirectoryReader.listChildren(parentUri.toString())
        }.onFailure { error ->
            ReaderDiagnosticLog.error("local_video_library_subtitles_failed uri=$videoUri", error)
        }.getOrDefault(emptyList())
        return findSidecarSubtitles(
            videoFileName = videoFileName,
            candidates = siblings,
            nameOf = FileDirectoryBrowserItem::name,
            isDirectoryOf = FileDirectoryBrowserItem::isDirectory,
        ).map { subtitle ->
            VideoSubtitleOpenRequest(
                uri = subtitle.uri,
                displayName = subtitle.name,
            )
        }
    }

    private suspend fun webDavVideoLibrarySubtitles(
        accountId: String,
        remotePath: String,
        videoFileName: String,
    ): List<WebDavSubtitleOpenRequest> {
        val client = webDavResolver.clientFor(accountId) ?: return emptyList()
        val parentPath = parentWebDavDirectoryPath(remotePath)
        val siblings = runCatching {
            client.list(parentPath)
        }.onFailure { error ->
            ReaderDiagnosticLog.error("webdav_video_library_subtitles_failed path=$remotePath", error)
        }.getOrDefault(emptyList())
        return findSidecarSubtitles(
            videoFileName = videoFileName,
            candidates = siblings,
            nameOf = WebDavItem::name,
            isDirectoryOf = WebDavItem::isDirectory,
        ).map { subtitle ->
            WebDavSubtitleOpenRequest(
                remotePath = subtitle.path,
                displayName = subtitle.name,
                size = subtitle.size,
                etag = subtitle.etag,
                lastModified = subtitle.lastModified,
                mimeType = mimeTypeForMediaFileName(subtitle.name),
            )
        }
    }

    private fun webDavVideoRequestForItem(item: WebDavItem): WebDavVideoOpenRequest =
        WebDavVideoOpenRequest(
            accountId = currentWebDavAccountId(),
            remotePath = item.path,
            displayName = item.name,
            size = item.size,
            etag = item.etag,
            lastModified = item.lastModified,
            mimeType = mimeTypeForMediaFileName(item.name),
            subtitles = emptyList(),
        )

    private fun currentWebDavAccountId(): String =
        webDavViewModel.activeAccountId() ?: webDavViewModel.accountId()
}

internal fun videoLibraryItemsNeedingThumbnails(
    items: List<VideoLibraryItemWithSources>,
): List<VideoLibraryItemWithSources> =
    items.filter { item ->
        item.item.thumbnailPath
            ?.let(::File)
            ?.isFile != true
    }

internal fun shouldSkipBrowserThumbnailRequest(
    failedRevision: Long?,
    requestRevision: Long,
): Boolean = failedRevision == requestRevision

private fun AppSettings.toVideoProxySettings(): VideoProxySettings =
    VideoProxySettings(
        seekOptimizationEnabled = videoSeekOptimizationEnabled,
        forwardPrefetchMode = videoForwardPrefetchMode,
        diagnosticsMode = videoProxyDiagnosticsMode,
    )
