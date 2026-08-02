package org.mubox.reader

import org.mubox.reader.data.SavedWebDavAccount
import org.mubox.reader.network.RecordingWebDavClient
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class AppWebDavResolverTest {
    @Test
    fun savedAccountTakesPriorityOverActiveConnectionFallback() = runTest {
        val saved = account("target", "https://saved.example")
        val resolver = resolver(
            savedAccount = saved,
            active = activeConnection(configuredAccountId = "target", baseUrl = "https://active.example"),
        )

        assertSame(saved, resolver.accountForPlayback("target"))
    }

    @Test
    fun matchingActiveConnectionProvidesUnsavedPlaybackAccount() = runTest {
        val resolver = resolver(
            active = activeConnection(
                configuredAccountId = "target",
                baseUrl = "  https://active.example/root  ",
                username = "reader",
                password = "secret",
            ),
        )

        assertEquals(
            account("target", "https://active.example/root", "reader", "secret"),
            resolver.accountForPlayback("target"),
        )
    }

    @Test
    fun unrelatedOrBlankActiveConnectionCannotProvidePlaybackAccount() = runTest {
        val unrelated = resolver(
            active = activeConnection(configuredAccountId = "other", baseUrl = "https://active.example"),
        )
        val blank = resolver(
            active = activeConnection(configuredAccountId = "target", baseUrl = "  "),
        )

        assertNull(unrelated.accountForPlayback("target"))
        assertNull(blank.accountForPlayback("target"))
    }

    @Test
    fun activeClientIsUsedOnlyForItsExactAccount() = runTest {
        val activeClient = RecordingWebDavClient(byteArrayOf(1))
        val savedClient = RecordingWebDavClient(byteArrayOf(2))
        var savedClientCalls = 0
        val resolver = AppWebDavResolver(
            loadSavedAccount = { null },
            loadSavedClient = {
                savedClientCalls += 1
                savedClient
            },
            activeConnection = {
                activeConnection(
                    activeAccountId = "active",
                    configuredAccountId = "active",
                    client = activeClient,
                )
            },
        )

        assertSame(activeClient, resolver.clientFor("active"))
        assertEquals(0, savedClientCalls)
        assertSame(savedClient, resolver.clientFor("other"))
        assertEquals(1, savedClientCalls)
    }

    private fun resolver(
        savedAccount: SavedWebDavAccount? = null,
        active: ActiveWebDavConnection,
    ): AppWebDavResolver =
        AppWebDavResolver(
            loadSavedAccount = { savedAccount },
            loadSavedClient = { null },
            activeConnection = { active },
        )

    private fun activeConnection(
        activeAccountId: String? = null,
        configuredAccountId: String,
        baseUrl: String = "",
        username: String = "",
        password: String = "",
        client: org.mubox.reader.core.remote.WebDavClient? = null,
    ): ActiveWebDavConnection =
        ActiveWebDavConnection(
            activeAccountId = activeAccountId,
            configuredAccountId = configuredAccountId,
            baseUrl = baseUrl,
            username = username,
            password = password,
            client = client,
        )

    private fun account(
        accountId: String,
        baseUrl: String,
        username: String = "",
        password: String = "",
    ): SavedWebDavAccount =
        SavedWebDavAccount(
            accountId = accountId,
            baseUrl = baseUrl,
            username = username,
            password = password,
        )
}
