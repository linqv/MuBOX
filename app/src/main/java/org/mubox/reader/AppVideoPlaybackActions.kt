package org.mubox.reader

import android.content.Context
import android.net.Uri
import org.mubox.reader.core.diagnostics.Diagnostics
import org.mubox.reader.core.model.history.WatchHistoryEntry
import org.mubox.reader.core.model.history.WatchMediaType
import org.mubox.reader.core.model.history.WatchSourceType
import org.mubox.reader.core.model.media.LocalVideoOpenRequest
import org.mubox.reader.core.model.media.VideoSubtitleOpenRequest
import org.mubox.reader.core.model.media.WebDavSubtitleOpenRequest
import org.mubox.reader.core.model.media.WebDavVideoOpenRequest
import org.mubox.reader.core.model.media.findSidecarSubtitles
import org.mubox.reader.core.model.media.mimeTypeForMediaFileName
import org.mubox.reader.core.model.settings.AppSettings
import org.mubox.reader.core.model.transfer.VideoDownloadRecord
import org.mubox.reader.core.model.videolibrary.VideoLibraryItemWithSources
import org.mubox.reader.core.model.videolibrary.VideoSourceType
import org.mubox.reader.core.remote.WebDavItem
import org.mubox.reader.core.remote.WebDavClientFactory
import org.mubox.reader.feature.filedirectory.FileDirectoryBrowserItem
import org.mubox.reader.feature.filedirectory.FileDirectoryViewModel
import org.mubox.reader.feature.filedirectory.LocalDirectoryReader
import org.mubox.reader.feature.videolibrary.VideoLibraryViewModel
import org.mubox.reader.feature.webdav.WebDavViewModel
import org.mubox.reader.video.player.VideoPlayerActivity
import org.mubox.reader.video.player.buildLocalDirectoryEpisodeQueue
import org.mubox.reader.video.player.buildWebDavDirectoryEpisodeQueue
import org.mubox.reader.video.player.localVideoEpisodeRequest
import org.mubox.reader.video.player.webDavVideoEpisodeRequest
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
    private val rememberWebDavClientFactory: (String, WebDavClientFactory) -> Unit,
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
                callbacks.setError(null)
                scope.launch {
                    runCatching {
                        rememberWebDavPlaybackFactory(accountId)
                        callbacks.launchPlayer(
                            VideoPlayerActivity.webDavIntent(
                                context = context,
                                request = request,
                                options = settings.toVideoPlayerOptions(),
                            ),
                        )
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
                rememberWebDavPlaybackFactory(accountId)
                callbacks.launchPlayer(
                    VideoPlayerActivity.webDavIntent(
                        context = context,
                        request = request,
                        options = settings.toVideoPlayerOptions(),
                        episodeQueue = episodeQueue,
                    ),
                )
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
                rememberWebDavPlaybackFactory(source.accountId)
                videoLibraryViewModel.markOpened(item.item.id)
                callbacks.launchPlayer(
                    VideoPlayerActivity.webDavIntent(
                        context = context,
                        request = playbackRequest,
                        options = settings.toVideoPlayerOptions(),
                    ),
                )
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

    private suspend fun rememberWebDavPlaybackFactory(accountId: String) {
        val factory = webDavResolver.clientFactoryForPlayback(accountId)
            ?: error("缺少 WebDAV 账号，请重新连接后再打开视频")
        rememberWebDavClientFactory(accountId, factory)
    }

    private fun currentWebDavAccountId(): String =
        webDavViewModel.activeAccountId() ?: webDavViewModel.accountId()
}
