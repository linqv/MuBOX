package com.example.comicdav

import com.example.comicdav.core.model.settings.VideoProxySettings
import com.example.comicdav.data.AppSettingsStore
import com.example.comicdav.core.remote.WebDavClientFactory
import com.example.comicdav.network.WebDavClientProvider
import com.example.comicdav.video.player.VideoPlayerDependencies
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

internal class AppVideoPlayerDependencies(
    private val settingsStore: AppSettingsStore,
    private val clientProvider: WebDavClientProvider,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : VideoPlayerDependencies {
    override suspend fun loadProxySettings(): VideoProxySettings = withContext(ioDispatcher) {
        settingsStore.settings.first().let { settings ->
            VideoProxySettings(
                seekOptimizationEnabled = settings.videoSeekOptimizationEnabled,
                forwardPrefetchMode = settings.videoForwardPrefetchMode,
                diagnosticsMode = settings.videoProxyDiagnosticsMode,
            )
        }
    }

    override suspend fun loadWebDavClientFactory(accountId: String): WebDavClientFactory? =
        clientProvider.clientFactoryFor(accountId)
}
