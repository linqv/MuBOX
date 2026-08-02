package org.mubox.reader

import org.mubox.reader.data.SavedWebDavAccount
import org.mubox.reader.core.remote.WebDavClient
import org.mubox.reader.core.remote.WebDavClientFactory
import org.mubox.reader.network.createWebDavClient

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
    private val loadSavedClientFactory: suspend (String) -> WebDavClientFactory? = { accountId ->
        loadSavedClient(accountId)?.let { client -> WebDavClientFactory { client } }
    },
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

    suspend fun clientFactoryForPlayback(accountId: String): WebDavClientFactory? {
        loadSavedClientFactory(accountId)?.let { return it }

        val active = activeConnection()
        val currentAccountId = active.activeAccountId ?: active.configuredAccountId
        val baseUrl = active.baseUrl.trim()
        if (currentAccountId != accountId || baseUrl.isBlank()) return null
        val username = active.username
        val password = active.password
        return WebDavClientFactory {
            createWebDavClient(
                baseUrl = baseUrl,
                username = username,
                password = password,
            )
        }
    }
}
