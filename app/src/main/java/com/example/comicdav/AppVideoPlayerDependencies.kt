package com.example.comicdav

import com.example.comicdav.core.model.settings.VideoProxySettings
import com.example.comicdav.core.model.history.WatchHistoryMetadata
import com.example.comicdav.core.ports.WatchHistoryGateway
import com.example.comicdav.core.remote.WebDavClientFactory
import com.example.comicdav.data.AppSettingsStore
import com.example.comicdav.video.player.VideoPlayerDependencies
import com.example.comicdav.video.player.VideoPlaybackStateStore
import com.example.comicdav.video.proxy.VideoProxyManager
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

internal class AppVideoPlayerDependencies(
    private val settingsStore: AppSettingsStore,
    private val webDavClientFactories: AppWebDavPlaybackClientFactories,
    private val historyRepository: WatchHistoryGateway,
    private val legacyPlaybackStateStore: VideoPlaybackStateStore,
    private val proxyManager: VideoProxyManager,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : VideoPlayerDependencies {
    override fun videoProxyManager(): VideoProxyManager = proxyManager

    override suspend fun loadProxySettings(): VideoProxySettings = withContext(ioDispatcher) {
        settingsStore.settings.first().let { settings ->
            VideoProxySettings(
                seekOptimizationEnabled = settings.video.videoSeekOptimizationEnabled,
                forwardPrefetchMode = settings.video.videoForwardPrefetchMode,
            )
        }
    }

    override suspend fun loadWebDavClientFactory(accountId: String): WebDavClientFactory? =
        webDavClientFactories.load(accountId)

    override suspend fun loadPlaybackPosition(playbackKey: String?): Long =
        legacyPlaybackStateStore.loadPosition(playbackKey)

    override suspend fun savePlaybackPosition(
        playbackKey: String?,
        positionMillis: Long,
        durationMillis: Long,
    ) {
        legacyPlaybackStateStore.savePosition(playbackKey, positionMillis, durationMillis)
    }

    override suspend fun recordWatchHistory(
        metadata: WatchHistoryMetadata,
        positionMillis: Long,
        durationMillis: Long,
    ) {
        if (metadata.mediaKey.isBlank()) return
        historyRepository.upsert(
            metadata.entry(
                progress = positionMillis.coerceAtMost(durationMillis.takeIf { it > 0L } ?: Long.MAX_VALUE),
                total = durationMillis,
            ),
        )
    }
}
