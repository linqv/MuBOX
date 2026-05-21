package com.example.comicdav.video.proxy

import com.example.comicdav.data.SavedWebDavAccount
import com.example.comicdav.network.OkHttpWebDavClient
import com.example.comicdav.video.WebDavSubtitleOpenRequest
import com.example.comicdav.video.WebDavVideoOpenRequest
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

object VideoProxyManager {
    private val activeSessions = AtomicInteger(0)
    @Volatile
    private var scope = newScope()
    @Volatile
    private var proxy: MuBoxVideoProxy? = null

    suspend fun open(
        request: WebDavVideoOpenRequest,
        account: SavedWebDavAccount,
    ): ProxySession {
        val accountSnapshot = account.copy()
        val sessionProxy = synchronized(this) {
            activeSessions.incrementAndGet()
            proxy ?: MuBoxVideoProxy(
                coroutineScope = scope,
            ).also { proxy = it }
        }
        var registeredStreamCount = 1
        val registeredUrls = mutableListOf<String>()
        try {
            sessionProxy.start()
            val url = sessionProxy.register(request) {
                accountSnapshot.client()
            }
            registeredUrls += url
            val subtitleUrls = request.subtitles.map { subtitle ->
                sessionProxy.register(subtitle.asStreamRequest(accountId = request.accountId)) {
                    accountSnapshot.client()
                }.also { registeredUrls += it }
            }
            val streamIds = registeredUrls.map(MuBoxVideoProxy::streamIdFromUrl)
            registeredStreamCount = streamIds.size
            if (streamIds.size > 1) {
                activeSessions.addAndGet(streamIds.size - 1)
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
        val removed = proxy?.unregister(streamId) == true
        if (removed) releaseStreams(1)
    }

    fun close(streamIds: Iterable<String>) {
        streamIds.forEach(::close)
    }

    fun close(session: ProxySession) {
        close(session.streamIds)
    }

    fun shutdown() {
        synchronized(this) {
            proxy?.close()
            proxy = null
            activeSessions.set(0)
            scope.cancel()
            scope = newScope()
        }
    }

    private fun newScope(): CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private fun releaseStreams(count: Int) {
        if (activeSessions.updateAndGet { current -> (current - count).coerceAtLeast(0) } <= 0) {
            shutdown()
        }
    }

    private fun SavedWebDavAccount.client(): OkHttpWebDavClient =
        OkHttpWebDavClient(
            baseUrl = baseUrl,
            username = username,
            password = password,
        )

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
