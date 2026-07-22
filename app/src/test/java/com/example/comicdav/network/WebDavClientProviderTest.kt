package com.example.comicdav.network

import com.example.comicdav.data.SavedWebDavAccount
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
        val account = SavedWebDavAccount(
            accountId = "account-1",
            baseUrl = "https://dav.example.test",
            username = "reader",
            password = "secret",
        )
        val client = StubWebDavClient()
        val loadedIds = mutableListOf<String>()
        val createdAccounts = mutableListOf<SavedWebDavAccount>()
        val provider = WebDavClientProvider(
            loadAccount = { accountId ->
                loadedIds += accountId
                account.takeIf { it.accountId == accountId }
            },
            createClient = { saved ->
                createdAccounts += saved
                client
            },
            ioDispatcher = dispatcher,
        )

        val resolved = provider.clientFor("account-1")

        assertSame(client, resolved)
        assertEquals(listOf("account-1"), loadedIds)
        assertEquals(listOf(account), createdAccounts)
    }

    @Test
    fun missingAccountDoesNotCreateAClient() = runTest(dispatcher) {
        var createCalls = 0
        val provider = WebDavClientProvider(
            loadAccount = { null },
            createClient = {
                createCalls += 1
                StubWebDavClient()
            },
            ioDispatcher = dispatcher,
        )

        assertNull(provider.clientFor("missing"))
        assertEquals(0, createCalls)
    }

    private class StubWebDavClient : WebDavClient {
        override suspend fun list(path: String): List<WebDavItem> = emptyList()

        override suspend fun head(path: String): RemoteFileInfo = error("not used")

        override suspend fun readRange(path: String, start: Long, endInclusive: Long): ByteArray = error("not used")

        override suspend fun download(path: String, target: File, onBytesRead: (Long) -> Unit): Long = error("not used")
    }
}
