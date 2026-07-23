package com.example.comicdav

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.example.comicdav.core.model.settings.AppSettings
import com.example.comicdav.core.model.settings.VideoProxySettings
import com.example.comicdav.data.VideoDownloadRecord
import com.example.comicdav.data.videolibrary.VideoLibraryItemWithSources
import com.example.comicdav.data.videolibrary.VideoSourceType
import com.example.comicdav.feature.filedirectory.FileDirectoryBrowserItem
import com.example.comicdav.feature.reader.ReaderDiagnosticLog
import com.example.comicdav.core.remote.WebDavItem
import com.example.comicdav.core.model.media.LocalVideoOpenRequest
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
    private val container: AppContainer,
    private val viewModels: AppViewModels,
    private val webDavResolver: AppWebDavResolver,
    private val callbacks: AppVideoActionCallbacks,
) {
    private val webDavViewModel = viewModels.webDav
    private val fileDirectoryViewModel = viewModels.fileDirectory
    private val videoLibraryViewModel = viewModels.videoLibrary

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
                when (item.item.sourceType) {
                    VideoSourceType.LOCAL -> {
                        val source = item.localSource ?: error("缺少本地视频来源")
                        extractLocalVideoThumbnail(
                            uri = source.uri,
                            size = source.size,
                            lastModified = source.lastModified,
                        ) ?: error("未能提取视频缩略图")
                    }
                    VideoSourceType.WEBDAV -> {
                        val source = item.webDavSource ?: error("缺少 WebDAV 视频来源")
                        extractWebDavVideoThumbnail(
                            WebDavVideoOpenRequest(
                                accountId = source.accountId,
                                remotePath = source.remotePath,
                                displayName = source.fileName,
                                size = source.size,
                                etag = source.etag,
                                lastModified = source.lastModified,
                                mimeType = mimeTypeForMediaFileName(source.fileName),
                            ),
                        ) ?: error("未能提取视频缩略图")
                    }
                }
            }.fold(
                onSuccess = { thumbnailPath ->
                    container.videoLibraryRepository.updateThumbnailPath(item.item.id, thumbnailPath)
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
    ): String? =
        container.videoThumbnailExtractor.extractFromContentUri(
            context = context,
            uri = Uri.parse(uri),
            stableKey = "local:$uri:${size ?: -1}:${lastModified ?: -1}",
        )

    private suspend fun extractWebDavVideoThumbnail(request: WebDavVideoOpenRequest): String? {
        val clientFactory = webDavResolver.clientFactoryForPlayback(request.accountId)
            ?: error("缺少 WebDAV 账号，请重新连接后再提取缩略图")
        val session = VideoProxyManager.open(
            request = request.copy(subtitles = emptyList()),
            clientFactory = clientFactory,
            proxySettings = settings.toVideoProxySettings(),
        )
        return try {
            container.videoThumbnailExtractor.extractFromUrl(
                url = session.url,
                stableKey = "webdav:${request.accountId}:${request.remotePath}:${request.size ?: -1}:${request.etag.orEmpty()}:${request.lastModified ?: -1}",
            )
        } finally {
            VideoProxyManager.close(session.streamIds)
        }
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

private fun AppSettings.toVideoProxySettings(): VideoProxySettings =
    VideoProxySettings(
        seekOptimizationEnabled = videoSeekOptimizationEnabled,
        forwardPrefetchMode = videoForwardPrefetchMode,
        diagnosticsMode = videoProxyDiagnosticsMode,
    )
