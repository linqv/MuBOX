package com.example.comicdav.network

import com.example.comicdav.core.remote.RemoteFileInfo
import com.example.comicdav.core.remote.WebDavClient
import com.example.comicdav.core.remote.WebDavItem
import java.io.File
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class WebDavClientProviderTest {
    private val dispatcher = StandardTestDispatcher()

    @Test
    fun resolvesAClientFromStoredCredentialsWithoutBrowserState() = runTest(dispatcher) {
        val credentials = WebDavCredentialsSnapshot(
            baseUrl = "https://dav.example.test",
            username = "reader",
            password = "secret",
        )
        val client = StubWebDavClient()
        val loadedIds = mutableListOf<String>()
        val createdCredentials = mutableListOf<WebDavCredentialsSnapshot>()
        val provider = WebDavClientProvider(
            loadCredentials = { accountId ->
                loadedIds += accountId
                credentials.takeIf { accountId == "account-1" }
            },
            createClient = { snapshot ->
                createdCredentials += snapshot
                client
            },
            ioDispatcher = dispatcher,
        )

        val resolved = provider.clientFor("account-1")

        assertSame(client, resolved)
        assertEquals(listOf("account-1"), loadedIds)
        assertEquals(listOf(credentials), createdCredentials)
    }

    @Test
    fun missingAccountDoesNotCreateAClient() = runTest(dispatcher) {
        var createCalls = 0
        val provider = WebDavClientProvider(
            loadCredentials = { null },
            createClient = {
                createCalls += 1
                StubWebDavClient()
            },
            ioDispatcher = dispatcher,
        )

        assertNull(provider.clientFor("missing"))
        assertEquals(0, createCalls)
    }

    @Test
    fun playbackFactoryKeepsTheLoadedCredentialSnapshot() = runTest(dispatcher) {
        var current = WebDavCredentialsSnapshot("https://first.example", "reader", "first-secret")
        val createdCredentials = mutableListOf<WebDavCredentialsSnapshot>()
        val provider = WebDavClientProvider(
            loadCredentials = { current },
            createClient = { snapshot ->
                createdCredentials += snapshot
                StubWebDavClient()
            },
            ioDispatcher = dispatcher,
        )

        val factory = checkNotNull(provider.clientFactoryFor("account-1"))
        current = WebDavCredentialsSnapshot("https://second.example", "reader", "second-secret")
        factory.create()
        factory.create()

        assertEquals(
            listOf(
                WebDavCredentialsSnapshot("https://first.example", "reader", "first-secret"),
                WebDavCredentialsSnapshot("https://first.example", "reader", "first-secret"),
            ),
            createdCredentials,
        )
    }

    private class StubWebDavClient : WebDavClient {
        override suspend fun list(path: String): List<WebDavItem> = emptyList()

        override suspend fun head(path: String): RemoteFileInfo = error("not used")

        override suspend fun readRange(path: String, start: Long, endInclusive: Long): ByteArray = error("not used")

        override suspend fun download(path: String, target: File, onBytesRead: (Long) -> Unit): Long = error("not used")
    }
}
