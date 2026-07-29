package com.example.comicdav

import android.content.Context
import android.net.Uri
import com.example.comicdav.core.diagnostics.Diagnostics
import com.example.comicdav.core.model.history.WatchHistoryEntry
import com.example.comicdav.core.model.history.WatchMediaType
import com.example.comicdav.core.model.history.WatchSourceType
import com.example.comicdav.core.model.media.LocalVideoOpenRequest
import com.example.comicdav.core.model.media.VideoSubtitleOpenRequest
import com.example.comicdav.core.model.media.WebDavSubtitleOpenRequest
import com.example.comicdav.core.model.media.WebDavVideoOpenRequest
import com.example.comicdav.core.model.media.findSidecarSubtitles
import com.example.comicdav.core.model.media.mimeTypeForMediaFileName
import com.example.comicdav.core.model.settings.AppSettings
import com.example.comicdav.core.model.transfer.VideoDownloadRecord
import com.example.comicdav.core.model.videolibrary.VideoLibraryItemWithSources
import com.example.comicdav.core.model.videolibrary.VideoSourceType
import com.example.comicdav.core.remote.WebDavItem
import com.example.comicdav.feature.filedirectory.FileDirectoryBrowserItem
import com.example.comicdav.feature.filedirectory.FileDirectoryViewModel
import com.example.comicdav.feature.filedirectory.LocalDirectoryReader
import com.example.comicdav.feature.videolibrary.VideoLibraryViewModel
import com.example.comicdav.feature.webdav.WebDavViewModel
import com.example.comicdav.video.player.VideoPlayerActivity
import com.example.comicdav.video.player.buildLocalDirectoryEpisodeQueue
import com.example.comicdav.video.player.buildWebDavDirectoryEpisodeQueue
import com.example.comicdav.video.player.localVideoEpisodeRequest
import com.example.comicdav.video.player.webDavVideoEpisodeRequest
import com.example.comicdav.video.proxy.startWebDavVideoPlayback
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** Coordinates player requests and subtitle discovery for every video source. */
internal class AppVideoPlaybackActions(
    private val context: Context,
    private val scope: CoroutineScope,
    private val settings: AppSettings,
    private val localDirectoryReader: LocalDirectoryReader,
    private val diagnostics: Diagnostics,
    private val fileDirectoryViewModel: FileDirectoryViewModel,
    private val webDavViewModel: WebDavViewModel,
    private val videoLibraryViewModel: VideoLibraryViewModel,
    private val webDavResolver: AppWebDavResolver,
    private val callbacks: AppVideoActionCallbacks,
) {
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
            diagnostics.error("play_video_download_failed uri=${record.localUri}", error)
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

    private suspend fun localVideoLibrarySubtitles(
        videoUri: String,
        videoFileName: String,
    ): List<VideoSubtitleOpenRequest> {
        val parentUri = parentDocumentUriForLocalVideo(Uri.parse(videoUri)) ?: return emptyList()
        val siblings = runCatching {
            localDirectoryReader.listChildren(parentUri.toString())
        }.onFailure { error ->
            diagnostics.error("local_video_library_subtitles_failed uri=$videoUri", error)
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
            diagnostics.error("webdav_video_library_subtitles_failed path=$remotePath", error)
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

    private fun currentWebDavAccountId(): String =
        webDavViewModel.activeAccountId() ?: webDavViewModel.accountId()
}
