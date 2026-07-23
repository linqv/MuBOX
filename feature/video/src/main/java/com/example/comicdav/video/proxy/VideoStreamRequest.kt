package com.example.comicdav.video.proxy

import com.example.comicdav.core.model.settings.VideoProxySettings
import com.example.comicdav.core.remote.WebDavClient
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class VideoStreamRequest(
    val streamId: String,
    val accountId: String,
    val remotePath: String,
    val displayName: String,
    val size: Long?,
    val etag: String?,
    val lastModified: Long?,
    val mimeType: String?,
    val proxySettings: VideoProxySettings = VideoProxySettings.DEFAULT,
)

internal class RegisteredVideoStream(
    val request: VideoStreamRequest,
    private val openClient: suspend () -> WebDavClient?,
) {
    private val clientMutex = Mutex()
    private var cachedClient: WebDavClient? = null

    suspend fun client(): WebDavClient? {
        cachedClient?.let { return it }
        return clientMutex.withLock {
            cachedClient ?: openClient()?.also { cachedClient = it }
        }
    }
}
