package com.example.comicdav.video.proxy

import com.example.comicdav.core.model.media.WebDavVideoOpenRequest
import com.example.comicdav.core.remote.WebDavClientFactory
import com.example.comicdav.core.model.settings.VideoProxySettings

suspend fun startWebDavVideoPlayback(
    request: WebDavVideoOpenRequest,
    clientFactory: WebDavClientFactory,
    proxySettings: VideoProxySettings = VideoProxySettings.DEFAULT,
    proxyManager: VideoProxyManager,
    openProxy: suspend (WebDavVideoOpenRequest, WebDavClientFactory, VideoProxySettings) -> ProxySession =
        proxyManager::open,
    closeProxy: (String) -> Unit = proxyManager::close,
    startPlayback: (ProxySession) -> Unit,
) {
    val session = openProxy(request, clientFactory, proxySettings)
    var launched = false
    try {
        startPlayback(session)
        launched = true
    } finally {
        if (!launched) {
            session.streamIds.forEach(closeProxy)
        }
    }
}
