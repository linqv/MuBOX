package com.example.comicdav.video.proxy

import com.example.comicdav.data.SavedWebDavAccount
import com.example.comicdav.network.OkHttpWebDavClient
import com.example.comicdav.video.WebDavVideoOpenRequest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

object VideoProxyManager {
    private val activeSessions = AtomicInteger(0)
    private val activeAccounts = ConcurrentHashMap<String, SavedWebDavAccount>()
    @Volatile
    private var scope = newScope()
    @Volatile
    private var proxy: MuBoxVideoProxy? = null

    suspend fun open(
        request: WebDavVideoOpenRequest,
        account: SavedWebDavAccount,
    ): ProxySession {
        activeAccounts[account.accountId] = account
        val sessionProxy = synchronized(this) {
            proxy ?: MuBoxVideoProxy(
                clientProvider = { accountId ->
                    activeAccounts[accountId]?.let { saved ->
                        OkHttpWebDavClient(
                            baseUrl = saved.baseUrl,
                            username = saved.username,
                            password = saved.password,
                        )
                    }
                },
                coroutineScope = scope,
            ).also { proxy = it }
        }
        sessionProxy.start()
        val url = sessionProxy.register(request)
        activeSessions.incrementAndGet()
        return ProxySession(proxy = sessionProxy, streamId = url.substringAfterLast('/'), url = url)
    }

    fun close(streamId: String) {
        proxy?.unregister(streamId)
        if (activeSessions.updateAndGet { current -> (current - 1).coerceAtLeast(0) } <= 0) {
            shutdown()
        }
    }

    fun shutdown() {
        synchronized(this) {
            proxy?.close()
            proxy = null
            activeSessions.set(0)
            activeAccounts.clear()
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
