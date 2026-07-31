package com.example.comicdav

import android.content.Context
import android.content.Intent
import com.example.comicdav.core.model.history.WatchHistoryEntry
import com.example.comicdav.core.model.history.WatchMediaType
import com.example.comicdav.core.model.history.historyEntriesNeedingThumbnails
import com.example.comicdav.core.model.settings.AppSettings
import com.example.comicdav.core.model.settings.VideoProxySettings
import com.example.comicdav.core.model.transfer.VideoDownloadRecord
import com.example.comicdav.core.model.library.LibraryItemWithSources
import com.example.comicdav.core.model.videolibrary.VideoLibraryItemWithSources
import com.example.comicdav.core.model.videolibrary.VideoSourceType
import com.example.comicdav.ui.directorylisting.MAX_DIRECTORY_VIDEO_THUMBNAILS
import com.example.comicdav.feature.filedirectory.FileDirectoryBrowserItem
import com.example.comicdav.feature.webdav.mediaKind
import com.example.comicdav.core.remote.WebDavItem
import com.example.comicdav.core.model.media.fileDirectoryBrowserVideoThumbnailVersion
import com.example.comicdav.core.model.media.MediaKind
import com.example.comicdav.core.model.media.WebDavVideoOpenRequest
import com.example.comicdav.core.model.media.mimeTypeForMediaFileName
import com.example.comicdav.core.model.media.webDavBrowserVideoThumbnailVersion
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal data class AppVideoActionCallbacks(
    val launchPlayer: (Intent) -> Unit,
    val setError: (String?) -> Unit,
    val setActionMessage: (String?) -> Unit,
    val clearSelectionIf: (predicate: (AppSelection) -> Boolean) -> Unit,
)

internal class AppVideoActions(
    private val context: Context,
    private val scope: CoroutineScope,
    private val settings: AppSettings,
    private val services: AppVideoMediaServices,
    presenters: AppVideoPresenters,
    private val webDavResolver: AppWebDavResolver,
    private val callbacks: AppVideoActionCallbacks,
) {
    private val webDavViewModel = presenters.webDav
    private val fileDirectoryViewModel = presenters.fileDirectory
    private val videoLibraryViewModel = presenters.videoLibrary
    private val playbackActions = AppVideoPlaybackActions(
        context = context,
        scope = scope,
        settings = settings,
        localDirectoryReader = services.localDirectoryReader,
        diagnostics = services.diagnostics,
        fileDirectoryViewModel = fileDirectoryViewModel,
        webDavViewModel = webDavViewModel,
        videoLibraryViewModel = videoLibraryViewModel,
        webDavResolver = webDavResolver,
        rememberWebDavClientFactory = services.webDavPlaybackClientFactories::remember,
        callbacks = callbacks,
    )
    private val thumbnailLoader = AppVideoThumbnailLoader(
        context = context,
        settings = settings,
        videoThumbnailExtractor = services.videoThumbnailExtractor,
        historyThumbnailExtractor = services.historyThumbnailExtractor,
        localComicOpener = services.localComicOpener,
        coverExtractor = services.coverExtractor,
        webDavResolver = webDavResolver,
        videoProxyManager = services.videoProxyManager,
    )
    private val failedBrowserThumbnailRequestRevisions =
        object : LinkedHashMap<String, Long>(MAX_DIRECTORY_VIDEO_THUMBNAILS, 0.75f, true) {
            override fun removeEldestEntry(
                eldest: MutableMap.MutableEntry<String, Long>?,
            ): Boolean = size > MAX_DIRECTORY_VIDEO_THUMBNAILS
        }
    private val browserThumbnailRequests = BrowserThumbnailRequestCoordinator(scope)

    fun openLocalDirectoryVideo(item: FileDirectoryBrowserItem) =
        playbackActions.openLocalDirectoryVideo(item)

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
            thumbnailLoader.extractLocal(
                uri = item.uri,
                size = item.size,
                lastModified = item.lastModified,
                extractor = services.browserVideoThumbnailExtractor,
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
        val stableKey = thumbnailLoader.webDavBrowserStableKey(
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
            thumbnailLoader.extractWebDav(
                request = request,
                extractor = services.browserVideoThumbnailExtractor,
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

    fun openHistoryEntry(entry: WatchHistoryEntry) =
        playbackActions.openHistoryEntry(entry)

    fun favoriteLocalDirectoryVideo(item: FileDirectoryBrowserItem) {
        scope.launch {
            runCatching {
                val thumbnailPath = if (settings.videoLibraryThumbnailsEnabled) {
                    runCatching {
                        thumbnailLoader.extractLocal(
                            uri = item.uri,
                            size = item.size,
                            lastModified = item.lastModified,
                        )
                    }.onFailure { error ->
                        services.diagnostics.error("extract_local_video_thumbnail_failed uri=${item.uri}", error)
                    }.getOrNull()
                } else {
                    null
                }
                services.library.addLocal(item, thumbnailPath)
            }.fold(
                onSuccess = {
                    callbacks.clearSelectionIf { it is AppSelection.DirectoryVideo }
                    videoLibraryViewModel.showMessage("已将 ${item.name} 加入影视库")
                    fileDirectoryViewModel.showMessage("已将 ${item.name} 加入影视库")
                },
                onFailure = { error ->
                    services.diagnostics.error("favorite_local_directory_video_failed uri=${item.uri}", error)
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
                        thumbnailLoader.extractWebDav(request)
                    }.onFailure { error ->
                        services.diagnostics.error("extract_webdav_video_thumbnail_failed path=${item.path}", error)
                    }.getOrNull()
                } else {
                    null
                }
                services.library.addWebDav(accountId, item, thumbnailPath)
            }.fold(
                onSuccess = {
                    callbacks.clearSelectionIf { it is AppSelection.WebDavFile }
                    callbacks.setActionMessage("已将 ${item.name} 加入影视库")
                    videoLibraryViewModel.showMessage("已将 ${item.name} 加入影视库")
                    fileDirectoryViewModel.showMessage("已将 ${item.name} 加入影视库")
                },
                onFailure = { error ->
                    callbacks.setError(error.message ?: "添加 WebDAV 视频失败")
                    services.diagnostics.error("add_webdav_video_library_failed path=${item.path}", error)
                    videoLibraryViewModel.showError(error.message ?: "添加 WebDAV 视频失败")
                    fileDirectoryViewModel.showError(error.message ?: "添加 WebDAV 视频失败")
                },
            )
        }
    }

    fun openWebDavVideo(item: WebDavItem) =
        playbackActions.openWebDavVideo(item)

    fun openVideoLibraryItem(item: VideoLibraryItemWithSources) =
        playbackActions.openVideoLibraryItem(item)

    fun removeVideoLibraryItem(item: VideoLibraryItemWithSources) {
        scope.launch {
            runCatching {
                item.item.thumbnailPath?.let { path ->
                    withContext(Dispatchers.IO) {
                        File(path).takeIf { it.isFile }?.delete()
                    }
                }
                services.library.remove(item)
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
                thumbnailLoader.extractVideoLibrary(item, forceRefresh = true)
            }.fold(
                onSuccess = { thumbnailPath ->
                    services.library.updateThumbnail(item, thumbnailPath)
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
                    services.diagnostics.error("refresh_video_thumbnail_failed id=${item.item.id}", error)
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
                        val thumbnailPath = thumbnailLoader.extractVideoLibrary(item)
                        services.library.updateThumbnail(item, thumbnailPath)
                        videoLibraryViewModel.onVideoThumbnailExtracted(
                            videoLibraryItemId = item.item.id,
                            thumbnailPath = thumbnailPath,
                        )
                        succeeded += 1
                    } catch (error: CancellationException) {
                        throw error
                    } catch (error: Throwable) {
                        failed += 1
                        services.diagnostics.error(
                            "batch_extract_video_thumbnail_failed id=${item.item.id}",
                            error,
                        )
                    }
                }
                historyTargets.forEach { entry ->
                    try {
                        thumbnailLoader.extractHistory(entry)
                        videoLibraryViewModel.onHistoryThumbnailExtracted(entry.mediaKey)
                        succeeded += 1
                    } catch (error: CancellationException) {
                        throw error
                    } catch (error: Throwable) {
                        failed += 1
                        services.diagnostics.error(
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
                services.library.updateThumbnail(item, null)
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

    fun playVideoDownloadRecord(record: VideoDownloadRecord) =
        playbackActions.playVideoDownloadRecord(record)

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

internal fun AppSettings.toVideoProxySettings(): VideoProxySettings =
    VideoProxySettings(
        seekOptimizationEnabled = videoSeekOptimizationEnabled,
        forwardPrefetchMode = videoForwardPrefetchMode,
        diagnosticsMode = videoProxyDiagnosticsMode,
    )
