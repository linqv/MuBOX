package com.example.comicdav.video.player

import com.example.comicdav.core.model.history.WatchHistoryMetadata
import com.example.comicdav.core.model.settings.VideoProxySettings
import com.example.comicdav.core.remote.WebDavClientFactory

interface VideoPlayerDependencies {
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
