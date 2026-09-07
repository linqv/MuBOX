package org.mubox.reader

import android.content.Context
import android.content.Intent
import org.mubox.reader.core.model.history.WatchHistoryEntry
import org.mubox.reader.core.model.history.WatchMediaType
import org.mubox.reader.core.model.history.historyEntriesNeedingThumbnails
import org.mubox.reader.core.model.history.historyThumbnailStableKey
import org.mubox.reader.core.model.settings.AppSettings
import org.mubox.reader.core.model.settings.VideoProxySettings
import org.mubox.reader.core.model.transfer.VideoDownloadRecord
import org.mubox.reader.core.model.library.LibraryItemWithSources
import org.mubox.reader.core.model.videolibrary.VideoLibraryItemWithSources
import org.mubox.reader.ui.directorylisting.MAX_DIRECTORY_VIDEO_THUMBNAILS
import org.mubox.reader.feature.filedirectory.FileDirectoryBrowserItem
import org.mubox.reader.feature.webdav.mediaKind
import org.mubox.reader.core.remote.WebDavItem
import org.mubox.reader.core.model.media.fileDirectoryBrowserVideoThumbnailVersion
import org.mubox.reader.core.model.media.fileDirectoryVideoThumbnailVersion
import org.mubox.reader.core.model.media.hasReliableFileDirectoryVideoThumbnailVersion
import org.mubox.reader.core.model.media.hasReliableWebDavVideoThumbnailVersion
import org.mubox.reader.core.model.media.MediaKind
import org.mubox.reader.core.model.media.WebDavVideoOpenRequest
import org.mubox.reader.core.model.media.mimeTypeForMediaFileName
import org.mubox.reader.core.model.media.resolvedVideoThumbnailPath
import org.mubox.reader.core.model.media.videoThumbnailStableKey
import org.mubox.reader.core.model.media.webDavBrowserVideoThumbnailVersion
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

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
        if (!settings.video.gridVideoThumbnailsEnabled || item.mediaKind != MediaKind.Video) return
        val requestRevision = fileDirectoryViewModel.uiState.thumbnailRequestRevision
        val version = fileDirectoryBrowserVideoThumbnailVersion(
            item = item,
            requestRevision = requestRevision,
        )
        val stableKey = fileDirectoryVideoThumbnailVersion(item)
        val requestKey = version
        if (
            shouldSkipBrowserThumbnailRequest(
                failedRevision = failedBrowserThumbnailRequestRevisions[requestKey],
                requestRevision = requestRevision,
            )
        ) {
            return
        }
        val thumbnailPath = browserThumbnailRequests.request(requestKey) {
            thumbnailLoader.extractLocal(
                uri = item.uri,
                size = item.size,
                lastModified = item.lastModified,
                stableKey = stableKey,
                forceRefresh = !hasReliableFileDirectoryVideoThumbnailVersion(item.lastModified),
            )
        }
        if (thumbnailPath == null) {
            failedBrowserThumbnailRequestRevisions[requestKey] = requestRevision
            return
        }
        failedBrowserThumbnailRequestRevisions -= requestKey
        fileDirectoryViewModel.onVideoThumbnailExtracted(
            uri = item.uri,
            version = version,
            thumbnailPath = thumbnailPath,
        )
        if (!syncLocalBrowserThumbnailWithVideoLibrary(item, thumbnailPath)) {
            videoLibraryViewModel.onSharedVideoThumbnailExtracted()
        }
    }

    suspend fun requestWebDavBrowserVideoThumbnail(item: WebDavItem) {
        if (!settings.video.gridVideoThumbnailsEnabled || item.mediaKind != MediaKind.Video) return
        val accountId = currentWebDavAccountId()
        if (accountId.substringBefore("|").isBlank()) return
        val request = webDavVideoRequestForItem(item)
        val requestRevision = webDavViewModel.uiState.thumbnailRequestRevision
        val version = webDavBrowserVideoThumbnailVersion(
            item = item,
            requestRevision = requestRevision,
        )
        val stableKey = thumbnailLoader.webDavStableKey(request)
        val hasReliableVersion = hasReliableWebDavVideoThumbnailVersion(item.etag, item.lastModified)
        val requestKey = if (hasReliableVersion) {
            stableKey
        } else {
            "$stableKey:directory-revision:$requestRevision"
        }
        if (
            shouldSkipBrowserThumbnailRequest(
                failedRevision = failedBrowserThumbnailRequestRevisions[requestKey],
                requestRevision = requestRevision,
            )
        ) {
            return
        }
        val thumbnailPath = browserThumbnailRequests.request(requestKey) {
            thumbnailLoader.extractWebDav(
                request = request,
                stableKey = stableKey,
                forceRefresh = !hasReliableVersion,
            )
        }
        if (thumbnailPath == null) {
            failedBrowserThumbnailRequestRevisions[requestKey] = requestRevision
            return
        }
        failedBrowserThumbnailRequestRevisions -= requestKey
        if (currentWebDavAccountId() == accountId) {
            webDavViewModel.onVideoThumbnailExtracted(
                path = item.path,
                version = version,
                thumbnailPath = thumbnailPath,
            )
        }
        if (!syncWebDavBrowserThumbnailWithVideoLibrary(
            accountId = accountId,
            source = item,
            thumbnailPath = thumbnailPath,
        )) {
            videoLibraryViewModel.onSharedVideoThumbnailExtracted()
        }
    }

    fun openHistoryEntry(entry: WatchHistoryEntry) =
        playbackActions.openHistoryEntry(entry)

    fun favoriteLocalDirectoryVideo(item: FileDirectoryBrowserItem) {
        scope.launch {
            runCatching {
                val thumbnailPath = if (settings.video.videoLibraryThumbnailsEnabled) {
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
                val thumbnailPath = if (settings.video.videoLibraryThumbnailsEnabled) {
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
                // The thumbnail is shared with the source grid and watch history.
                // Removing only the library record must not invalidate those callers.
                services.library.remove(item)
            }.fold(
                onSuccess = {
                    videoLibraryViewModel.showMessage("已将 ${item.item.displayName} 移出影视库")
                },
                onFailure = { error ->
                    videoLibraryViewModel.showError(error.message ?: "移出影视库失败")
                },
            )
        }
    }

    fun extractMissingThumbnails(
        videoLibraryItems: List<VideoLibraryItemWithSources>,
        history: List<WatchHistoryEntry>,
        libraryItems: List<LibraryItemWithSources>,
    ) {
        if (!settings.video.videoLibraryThumbnailsEnabled && !settings.appearance.libraryCoversEnabled) {
            videoLibraryViewModel.showThumbnailExtractionResult(
                message = "请先在设置中开启封面或缩略图",
                isError = true,
            )
            return
        }
        if (videoLibraryViewModel.uiState.isExtractingThumbnails) return

        val videoTargets = if (settings.video.videoLibraryThumbnailsEnabled) {
            videoLibraryItemsNeedingThumbnails(videoLibraryItems, context.cacheDir)
        } else {
            emptyList()
        }
        val historyCandidates = historyEntriesNeedingThumbnails(
            history = history,
            comics = libraryItems,
            videos = videoLibraryItems,
            cacheDir = context.cacheDir,
        ).filter { entry ->
            val typeEnabled = when (entry.mediaType) {
                WatchMediaType.COMIC -> settings.appearance.libraryCoversEnabled
                WatchMediaType.VIDEO -> settings.video.videoLibraryThumbnailsEnabled
            }
            typeEnabled
        }
        val historyTargets = historyEntriesNotCoveredByVideoTargets(
            entries = historyCandidates,
            videoTargets = videoTargets,
        )
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
                        val artworkPath = thumbnailLoader.extractHistory(entry)
                        if (entry.mediaType == WatchMediaType.COMIC) {
                            syncComicLibraryCoverPath(entry, artworkPath, libraryItems)
                        }
                        videoLibraryViewModel.onHistoryThumbnailExtracted(
                            mediaKey = entry.mediaKey,
                            isVideo = entry.mediaType == WatchMediaType.VIDEO,
                        )
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

    /**
     * Cover extraction moves comics from the unversioned cover layout to
     * library-covers/v2 and deletes the old file, so the stored coverPath must
     * follow; otherwise the bookshelf grid loses a cover that still exists on disk.
     */
    private suspend fun syncComicLibraryCoverPath(
        entry: WatchHistoryEntry,
        coverPath: String,
        libraryItems: List<LibraryItemWithSources>,
    ) {
        val item = libraryItems.firstOrNull { candidate ->
            candidate.webDavSource?.let { source ->
                source.accountId == entry.accountId && source.remotePath == entry.sourceLocator
            } == true
        } ?: return
        if (item.item.coverPath == coverPath) return
        runCatching {
            services.comicLibrary.updateCoverPath(item.item.id, coverPath)
        }.onFailure { error ->
            services.diagnostics.error(
                "sync_comic_library_cover_path_failed id=${item.item.id}",
                error,
            )
        }
    }

    fun playVideoDownloadRecord(record: VideoDownloadRecord) =
        playbackActions.playVideoDownloadRecord(record)

    private suspend fun syncLocalBrowserThumbnailWithVideoLibrary(
        source: FileDirectoryBrowserItem,
        thumbnailPath: String,
    ): Boolean {
        val libraryItem = videoLibraryViewModel.uiState.items.firstOrNull { candidate ->
            candidate.localSource?.uri == source.uri
        } ?: return false
        return syncBrowserThumbnailWithVideoLibrary(libraryItem, thumbnailPath) {
            services.library.synchronizeLocalThumbnail(libraryItem, source, thumbnailPath)
        }
    }

    private suspend fun syncWebDavBrowserThumbnailWithVideoLibrary(
        accountId: String,
        source: WebDavItem,
        thumbnailPath: String,
    ): Boolean {
        val libraryItem = videoLibraryViewModel.uiState.items.firstOrNull { candidate ->
            candidate.webDavSource?.let { candidateSource ->
                candidateSource.accountId == accountId && candidateSource.remotePath == source.path
            } == true
        } ?: return false
        return syncBrowserThumbnailWithVideoLibrary(libraryItem, thumbnailPath) {
            services.library.synchronizeWebDavThumbnail(libraryItem, source, thumbnailPath)
        }
    }

    private suspend fun syncBrowserThumbnailWithVideoLibrary(
        item: VideoLibraryItemWithSources,
        thumbnailPath: String,
        persist: suspend () -> Unit,
    ): Boolean = try {
        persist()
        videoLibraryViewModel.onVideoThumbnailExtracted(
            videoLibraryItemId = item.item.id,
            thumbnailPath = thumbnailPath,
        )
        true
    } catch (error: CancellationException) {
        throw error
    } catch (error: Throwable) {
        services.diagnostics.error(
            "sync_browser_video_thumbnail_failed id=${item.item.id}",
            error,
        )
        false
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
    cacheDir: File? = null,
): List<VideoLibraryItemWithSources> =
    items.filter { item ->
        if (cacheDir == null) {
            item.item.thumbnailPath
                ?.let(::File)
                ?.isFile != true
        } else {
            resolvedVideoThumbnailPath(item, cacheDir) == null
        }
    }

internal fun historyEntriesNotCoveredByVideoTargets(
    entries: List<WatchHistoryEntry>,
    videoTargets: List<VideoLibraryItemWithSources>,
): List<WatchHistoryEntry> {
    val scheduledStableKeys = videoTargets.mapNotNullTo(mutableSetOf(), ::videoThumbnailStableKey)
    return entries.filterNot { entry ->
        entry.mediaType == WatchMediaType.VIDEO &&
            historyThumbnailStableKey(entry) in scheduledStableKeys
    }
}

internal fun shouldSkipBrowserThumbnailRequest(
    failedRevision: Long?,
    requestRevision: Long,
): Boolean = failedRevision == requestRevision

internal fun AppSettings.toVideoProxySettings(): VideoProxySettings =
    VideoProxySettings(
        seekOptimizationEnabled = video.videoSeekOptimizationEnabled,
        forwardPrefetchMode = video.videoForwardPrefetchMode,
    )
