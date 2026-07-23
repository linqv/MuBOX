package com.example.comicdav.video.player

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

internal fun VideoEpisode.toPlayerMediaContext(): VideoPlayerMediaContext =
    VideoPlayerMediaContext(
        displayName = displayName,
        source = if (source == VideoEpisodeSource.WEB_DAV) {
            VideoPlayerLaunchContract.SOURCE_WEB_DAV
        } else {
            VideoPlayerLaunchContract.SOURCE_LOCAL
        },
        remotePath = webDavRequest?.remotePath ?: localRequest?.uri,
    )
