package com.example.comicdav.video.player

import com.example.comicdav.core.model.settings.VideoProxySettings
import com.example.comicdav.core.remote.WebDavClientFactory

interface VideoPlayerDependencies {
    suspend fun loadProxySettings(): VideoProxySettings
    suspend fun loadWebDavClientFactory(accountId: String): WebDavClientFactory?
}

interface VideoPlayerDependenciesOwner {
    val videoPlayerDependencies: VideoPlayerDependencies
}
