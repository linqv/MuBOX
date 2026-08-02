package org.mubox.reader.network

import org.mubox.reader.core.diagnostics.Diagnostics
import org.mubox.reader.core.diagnostics.NoopDiagnostics
import org.mubox.reader.core.remote.WebDavClient
import org.mubox.reader.core.remote.WebDavClientFactory
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class WebDavCredentialsSnapshot(
    val baseUrl: String,
    val username: String,
    val password: String,
)

/** Resolves account credentials without mutating any browser or navigation state. */
class WebDavClientProvider(
    private val loadCredentials: suspend (String) -> WebDavCredentialsSnapshot?,
    private val diagnostics: Diagnostics = NoopDiagnostics,
    private val createClient: (WebDavCredentialsSnapshot) -> WebDavClient = { credentials ->
        OkHttpWebDavClient(
            baseUrl = credentials.baseUrl,
            username = credentials.username,
            password = credentials.password,
            diagnostics = WebDavNetworkDiagnostics(diagnostics),
        )
    },
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    suspend fun clientFor(accountId: String): WebDavClient? = withContext(ioDispatcher) {
        loadCredentials(accountId)?.let(createClient)
    }

    /** Captures one immutable credential snapshot for the lifetime of a playback session. */
    suspend fun clientFactoryFor(accountId: String): WebDavClientFactory? = withContext(ioDispatcher) {
        val credentials = loadCredentials(accountId) ?: return@withContext null
        WebDavClientFactory { createClient(credentials) }
    }
}
