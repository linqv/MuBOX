package org.mubox.reader.video.player

import org.mubox.reader.core.model.history.WatchHistoryMetadata
import org.mubox.reader.core.model.settings.VideoProxySettings
import org.mubox.reader.core.remote.WebDavClientFactory
import org.mubox.reader.video.proxy.VideoProxyManager

interface VideoPlayerDependencies {
    fun videoProxyManager(): VideoProxyManager = VideoProxyManager()
    suspend fun loadProxySettings(): VideoProxySettings
    suspend fun loadWebDavClientFactory(accountId: String): WebDavClientFactory?
    suspend fun loadPlaybackPosition(playbackKey: String?): Long = 0L
    suspend fun savePlaybackPosition(playbackKey: String?, positionMillis: Long, durationMillis: Long) = Unit
    suspend fun recordWatchHistory(
        metadata: WatchHistoryMetadata,
        positionMillis: Long,
        durationMillis: Long,
    ) = Unit
}

interface VideoPlayerDependenciesOwner {
    val videoPlayerDependencies: VideoPlayerDependencies
}
