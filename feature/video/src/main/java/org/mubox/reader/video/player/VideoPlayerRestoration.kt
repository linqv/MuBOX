package org.mubox.reader.video.player

import org.mubox.reader.core.model.history.WatchHistoryMetadata
import org.mubox.reader.core.model.history.WatchMediaType
import org.mubox.reader.core.model.history.WatchSourceType
import org.mubox.reader.core.model.media.fileDirectoryVideoThumbnailVersion
import org.mubox.reader.core.model.media.videoThumbnailFile
import org.mubox.reader.core.model.media.webDavVideoThumbnailStableKey
import java.io.File
internal data class RestoredVideoEpisodeSelection(
    val index: Int,
    val episode: VideoEpisode,
)

/** Returns a queue-backed episode only for an actual saved-instance restoration. */
internal fun restoredVideoEpisodeSelection(
    episodeQueue: VideoEpisodeQueue?,
    savedEpisodeIndex: Int?,
): RestoredVideoEpisodeSelection? {
    val index = savedEpisodeIndex ?: return null
    val episode = episodeQueue?.episodes?.getOrNull(index) ?: return null
    return RestoredVideoEpisodeSelection(index = index, episode = episode)
}

internal fun VideoEpisode.toPlayerMediaContext(cacheDir: File? = null): VideoPlayerMediaContext =
    VideoPlayerMediaContext(
        displayName = displayName,
        source = if (source == VideoEpisodeSource.WEB_DAV) {
            VideoPlayerLaunchContract.SOURCE_WEB_DAV
        } else {
            VideoPlayerLaunchContract.SOURCE_LOCAL
        },
        remotePath = webDavRequest?.remotePath ?: localRequest?.uri,
        artworkPath = cacheDir?.let(::cachedArtworkPath),
    )

internal fun VideoPlayerLaunchArguments.toCachedArtworkPath(cacheDir: File): String? {
    val stableKey = if (isWebDav) {
        webDavVideoThumbnailStableKey(
            accountId = accountId.orEmpty(),
            remotePath = remotePath.orEmpty(),
            size = size,
            etag = etag,
            lastModified = lastModified,
        )
    } else {
        fileDirectoryVideoThumbnailVersion(
            uri = remotePath ?: uri.orEmpty(),
            size = size,
            lastModified = lastModified,
        )
    }
    return cachedVideoArtworkPath(cacheDir, stableKey)
}

private fun VideoEpisode.cachedArtworkPath(cacheDir: File): String? {
    val stableKey = localRequest?.let { request ->
        fileDirectoryVideoThumbnailVersion(
            uri = request.uri,
            size = request.size,
            lastModified = request.lastModified,
        )
    } ?: requireNotNull(webDavRequest).let { request ->
        webDavVideoThumbnailStableKey(
            accountId = request.accountId,
            remotePath = request.remotePath,
            size = request.size,
            etag = request.etag,
            lastModified = request.lastModified,
        )
    }
    return cachedVideoArtworkPath(cacheDir, stableKey)
}

private fun cachedVideoArtworkPath(cacheDir: File, stableKey: String): String? =
    videoThumbnailFile(cacheDir, stableKey)
        .takeIf { it.isFile && it.length() > 0L }
        ?.absolutePath

internal fun VideoEpisode.toWatchHistoryMetadata(): WatchHistoryMetadata =
    localRequest?.let { request ->
        WatchHistoryMetadata(
            mediaKey = playbackKey,
            mediaType = WatchMediaType.VIDEO,
            title = request.displayName,
            sourceType = WatchSourceType.LOCAL,
            sourceLocator = request.uri,
            size = request.size,
            lastModified = request.lastModified,
        )
    } ?: requireNotNull(webDavRequest).let { request ->
        WatchHistoryMetadata(
            mediaKey = playbackKey,
            mediaType = WatchMediaType.VIDEO,
            title = request.displayName,
            sourceType = WatchSourceType.WEB_DAV,
            sourceLocator = request.remotePath,
            accountId = request.accountId,
            size = request.size,
            etag = request.etag,
            lastModified = request.lastModified,
        )
    }

internal fun VideoPlayerLaunchArguments.toWatchHistoryMetadata(playbackKey: String?): WatchHistoryMetadata =
    WatchHistoryMetadata(
        mediaKey = playbackKey.orEmpty(),
        mediaType = WatchMediaType.VIDEO,
        title = displayName,
        sourceType = if (isWebDav) WatchSourceType.WEB_DAV else WatchSourceType.LOCAL,
        sourceLocator = remotePath ?: uri.orEmpty(),
        accountId = accountId,
        size = size,
        etag = etag,
        lastModified = lastModified,
    )
