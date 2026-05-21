package com.example.comicdav.video.proxy

import com.example.comicdav.data.SavedWebDavAccount
import com.example.comicdav.video.WebDavVideoOpenRequest

internal suspend fun startWebDavVideoPlayback(
    request: WebDavVideoOpenRequest,
    account: SavedWebDavAccount,
    proxySettings: VideoProxySettings = VideoProxySettings.DEFAULT,
    openProxy: suspend (WebDavVideoOpenRequest, SavedWebDavAccount, VideoProxySettings) -> ProxySession = VideoProxyManager::open,
    closeProxy: (String) -> Unit = VideoProxyManager::close,
    startPlayback: (ProxySession) -> Unit,
) {
    val session = openProxy(request, account, proxySettings)
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
