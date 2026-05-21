package com.example.comicdav.video.proxy

import com.example.comicdav.data.SavedWebDavAccount
import com.example.comicdav.network.OkHttpWebDavClient
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
            proxy ?: MuBoxVideoProxy(
                coroutineScope = scope,
            ).also { proxy = it }
        }
        sessionProxy.start()
        val url = sessionProxy.register(request) {
            OkHttpWebDavClient(
                baseUrl = accountSnapshot.baseUrl,
                username = accountSnapshot.username,
                password = accountSnapshot.password,
            )
        }
        activeSessions.incrementAndGet()
        return ProxySession(proxy = sessionProxy, streamId = url.substringAfterLast('/'), url = url)
    }

    fun close(streamId: String) {
        val removed = proxy?.unregister(streamId) == true
        if (removed && activeSessions.updateAndGet { current -> (current - 1).coerceAtLeast(0) } <= 0) {
            shutdown()
        }
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
}

data class ProxySession(
    val proxy: MuBoxVideoProxy,
    val streamId: String,
    val url: String,
)
