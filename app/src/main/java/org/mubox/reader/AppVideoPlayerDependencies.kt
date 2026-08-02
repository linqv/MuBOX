package org.mubox.reader

import org.mubox.reader.core.model.settings.VideoProxySettings
import org.mubox.reader.core.model.history.WatchHistoryMetadata
import org.mubox.reader.core.ports.WatchHistoryGateway
import org.mubox.reader.core.remote.WebDavClientFactory
import org.mubox.reader.data.AppSettingsStore
import org.mubox.reader.video.player.VideoPlayerDependencies
import org.mubox.reader.video.player.VideoPlaybackStateStore
import org.mubox.reader.video.proxy.VideoProxyManager
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

internal class AppVideoPlayerDependencies(
    private val settingsStore: AppSettingsStore,
    private val webDavClientFactories: AppWebDavPlaybackClientFactories,
    private val historyRepository: WatchHistoryGateway,
    private val playbackStateStore: VideoPlaybackStateStore,
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
        playbackStateStore.loadPosition(playbackKey)

    override suspend fun savePlaybackPosition(
        playbackKey: String?,
        positionMillis: Long,
        durationMillis: Long,
    ) {
        playbackStateStore.savePosition(playbackKey, positionMillis, durationMillis)
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
