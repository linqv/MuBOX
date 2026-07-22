package com.example.comicdav

import com.example.comicdav.data.SavedWebDavAccount
import com.example.comicdav.network.WebDavClient

internal data class ActiveWebDavConnection(
    val activeAccountId: String?,
    val configuredAccountId: String,
    val baseUrl: String,
    val username: String,
    val password: String,
    val client: WebDavClient?,
)

internal class AppWebDavResolver(
    private val loadSavedAccount: suspend (String) -> SavedWebDavAccount?,
    private val loadSavedClient: suspend (String) -> WebDavClient?,
    private val activeConnection: () -> ActiveWebDavConnection,
) {
    suspend fun accountForPlayback(accountId: String): SavedWebDavAccount? {
        loadSavedAccount(accountId)?.let { return it }

        val active = activeConnection()
        val currentAccountId = active.activeAccountId ?: active.configuredAccountId
        val baseUrl = active.baseUrl.trim()
        if (currentAccountId != accountId || baseUrl.isBlank()) return null
        return SavedWebDavAccount(
            accountId = accountId,
            baseUrl = baseUrl,
            username = active.username,
            password = active.password,
        )
    }

    suspend fun clientFor(accountId: String): WebDavClient? {
        val active = activeConnection()
        if (active.client != null && active.activeAccountId == accountId) {
            return active.client
        }
        return loadSavedClient(accountId)
    }
}
