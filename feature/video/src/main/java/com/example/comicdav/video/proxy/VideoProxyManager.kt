package com.example.comicdav.video.proxy

import com.example.comicdav.core.model.media.WebDavSubtitleOpenRequest
import com.example.comicdav.core.model.media.WebDavVideoOpenRequest
import com.example.comicdav.core.remote.WebDavClientFactory
import com.example.comicdav.core.model.settings.VideoProxySettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

object VideoProxyManager {
    private val lifecycleLock = Any()
    private val cleanupScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var activeStreams = 0
    @Volatile
    private var scope = newScope()
    @Volatile
    private var proxy: MuBoxVideoProxy? = null

    suspend fun open(
        request: WebDavVideoOpenRequest,
        clientFactory: WebDavClientFactory,
        proxySettings: VideoProxySettings = VideoProxySettings.DEFAULT,
    ): ProxySession {
        val sessionProxy = reserveProxyStream()
        var registeredStreamCount = 1
        val registeredUrls = mutableListOf<String>()
        try {
            sessionProxy.start()
            val url = sessionProxy.register(request, proxySettings) {
                clientFactory.create()
            }
            registeredUrls += url
            val subtitleUrls = request.subtitles.map { subtitle ->
                sessionProxy.register(
                    request = subtitle.asStreamRequest(accountId = request.accountId),
                    proxySettings = proxySettings,
                ) {
                    clientFactory.create()
                }.also { registeredUrls += it }
            }
            val streamIds = registeredUrls.map(MuBoxVideoProxy::streamIdFromUrl)
            registeredStreamCount = streamIds.size
            if (streamIds.size > 1) {
                reserveAdditionalStreams(streamIds.size - 1)
            }
            return ProxySession(
                proxy = sessionProxy,
                streamId = streamIds.first(),
                url = url,
                subtitleUrls = subtitleUrls,
                streamIds = streamIds,
            )
        } catch (error: Throwable) {
            registeredUrls
                .map(MuBoxVideoProxy::streamIdFromUrl)
                .forEach(sessionProxy::unregister)
            releaseStreams(registeredStreamCount)
            throw error
        }
    }

    fun close(streamId: String) {
        val removed = synchronized(lifecycleLock) {
            proxy?.unregister(streamId) == true
        }
        if (removed) releaseStreams(1)
    }

    fun close(streamIds: Iterable<String>) {
        streamIds.forEach(::close)
    }

    fun close(session: ProxySession) {
        close(session.streamIds)
    }

    fun statistics(streamId: String): VideoProxyRuntimeStats? =
        synchronized(lifecycleLock) {
            proxy?.statistics(streamId)
        }

    fun shutdown() {
        synchronized(lifecycleLock) {
            shutdownLocked()
        }
    }

    private fun newScope(): CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private fun reserveProxyStream(): MuBoxVideoProxy =
        synchronized(lifecycleLock) {
            activeStreams += 1
            proxy ?: MuBoxVideoProxy(
                coroutineScope = scope,
            ).also { proxy = it }
        }

    private fun reserveAdditionalStreams(count: Int) {
        if (count <= 0) return
        synchronized(lifecycleLock) {
            activeStreams += count
        }
    }

    private fun releaseStreams(count: Int) {
        synchronized(lifecycleLock) {
            activeStreams = (activeStreams - count).coerceAtLeast(0)
            if (activeStreams <= 0) {
                shutdownLocked()
            }
        }
    }

    private fun shutdownLocked() {
        val closingProxy = proxy
        closingProxy?.close()
        proxy = null
        activeStreams = 0
        scope.cancel()
        scope = newScope()
        if (closingProxy != null) {
            cleanupScope.launch {
                closingProxy.awaitClosed()
            }
        }
    }

    private fun WebDavSubtitleOpenRequest.asStreamRequest(accountId: String): WebDavVideoOpenRequest =
        WebDavVideoOpenRequest(
            accountId = accountId,
            remotePath = remotePath,
            displayName = displayName,
            size = size,
            etag = etag,
            lastModified = lastModified,
            mimeType = mimeType,
        )
}

data class ProxySession(
    val proxy: MuBoxVideoProxy,
    val streamId: String,
    val url: String,
    val subtitleUrls: List<String> = emptyList(),
    val streamIds: List<String> = listOf(streamId),
)
