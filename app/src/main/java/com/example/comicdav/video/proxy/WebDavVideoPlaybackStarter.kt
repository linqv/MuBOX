package com.example.comicdav.video.proxy

import com.example.comicdav.data.SavedWebDavAccount
import com.example.comicdav.video.WebDavVideoOpenRequest

internal suspend fun startWebDavVideoPlayback(
    request: WebDavVideoOpenRequest,
    account: SavedWebDavAccount,
    openProxy: suspend (WebDavVideoOpenRequest, SavedWebDavAccount) -> ProxySession = VideoProxyManager::open,
    closeProxy: (String) -> Unit = VideoProxyManager::close,
    startPlayback: (ProxySession) -> Unit,
) {
    val session = openProxy(request, account)
    var launched = false
    try {
        startPlayback(session)
        launched = true
    } finally {
        if (!launched) {
            closeProxy(session.streamId)
        }
    }
}
