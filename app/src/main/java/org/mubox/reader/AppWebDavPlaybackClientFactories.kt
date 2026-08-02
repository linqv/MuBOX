package org.mubox.reader

import org.mubox.reader.core.remote.WebDavClientFactory

/**
 * Bridges a currently active, not-yet-persisted WebDAV connection into the application-owned
 * player dependencies without retaining an Activity or ViewModel.
 */
internal class AppWebDavPlaybackClientFactories(
    private val loadSavedFactory: suspend (String) -> WebDavClientFactory?,
) {
    @Volatile
    private var activeFallback: ActiveFallback? = null

    fun remember(
        accountId: String,
        factory: WebDavClientFactory,
    ) {
        activeFallback = ActiveFallback(accountId, factory)
    }

    suspend fun load(accountId: String): WebDavClientFactory? =
        loadSavedFactory(accountId)
            ?: activeFallback?.takeIf { it.accountId == accountId }?.factory

    private data class ActiveFallback(
        val accountId: String,
        val factory: WebDavClientFactory,
    )
}
