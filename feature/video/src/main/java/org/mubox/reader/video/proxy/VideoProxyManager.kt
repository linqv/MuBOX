package org.mubox.reader.video.proxy

import org.mubox.reader.core.diagnostics.Diagnostics
import org.mubox.reader.core.diagnostics.NoopDiagnostics
import org.mubox.reader.core.model.media.WebDavSubtitleOpenRequest
import org.mubox.reader.core.model.media.WebDavVideoOpenRequest
import org.mubox.reader.core.remote.WebDavClientFactory
import org.mubox.reader.core.model.settings.VideoProxySettings
import java.io.Closeable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

/**
 * Owns one aggregate proxy/cache lifecycle. A manager may be shared by playback and thumbnail
 * consumers so all active streams stay under one memory-cache budget.
 */
class VideoProxyManager(
    private val diagnostics: Diagnostics = NoopDiagnostics,
) : Closeable {
    private val lifecycleLock = Any()
    private var activeStreams = 0
    private var closed = false
    private var scope: CoroutineScope? = null
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

    override fun close() {
        synchronized(lifecycleLock) {
            if (closed) return
            closed = true
            shutdownLocked()
        }
    }

    private fun newScope(): CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private fun reserveProxyStream(): MuBoxVideoProxy =
        synchronized(lifecycleLock) {
            check(!closed) { "Video proxy manager is closed" }
            activeStreams += 1
            proxy ?: MuBoxVideoProxy(
                coroutineScope = scope ?: newScope().also { scope = it },
                diagnostics = diagnostics,
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
        scope?.cancel()
        scope = null
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
    val streamId: String,
    val url: String,
    val subtitleUrls: List<String> = emptyList(),
    val streamIds: List<String> = listOf(streamId),
)
