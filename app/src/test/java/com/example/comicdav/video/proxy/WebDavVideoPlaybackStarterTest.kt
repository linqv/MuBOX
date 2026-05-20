package com.example.comicdav.video.proxy

import com.example.comicdav.data.SavedWebDavAccount
import com.example.comicdav.video.WebDavVideoOpenRequest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WebDavVideoPlaybackStarterTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @After
    fun tearDown() {
        scope.cancel()
    }

    @Test
    fun closesProxySessionWhenStartPlaybackFails() = runTest {
        val closedStreamIds = mutableListOf<String>()
        val session = proxySession("stream-1")

        val result = runCatching {
            startWebDavVideoPlayback(
                request = request(),
                account = account(),
                openProxy = { _, _ -> session },
                closeProxy = { closedStreamIds += it },
                startPlayback = { error("activity launch failed") },
            )
        }

        assertTrue(result.isFailure)
        assertEquals(listOf("stream-1"), closedStreamIds)
    }

    @Test
    fun keepsProxySessionWhenStartPlaybackSucceeds() = runTest {
        val closedStreamIds = mutableListOf<String>()
        val session = proxySession("stream-1")

        startWebDavVideoPlayback(
            request = request(),
            account = account(),
            openProxy = { _, _ -> session },
            closeProxy = { closedStreamIds += it },
            startPlayback = {},
        )

        assertEquals(emptyList<String>(), closedStreamIds)
    }

    private fun proxySession(streamId: String): ProxySession =
        ProxySession(
            proxy = MuBoxVideoProxy(clientProvider = { null }, coroutineScope = scope),
            streamId = streamId,
            url = "http://127.0.0.1:1/stream/$streamId",
        )

    private fun request(): WebDavVideoOpenRequest =
        WebDavVideoOpenRequest(
            accountId = "account-1",
            remotePath = "/movie.mp4",
            displayName = "movie.mp4",
            size = 10L,
            etag = null,
            lastModified = null,
            mimeType = "video/mp4",
        )

    private fun account(): SavedWebDavAccount =
        SavedWebDavAccount(
            accountId = "account-1",
            baseUrl = "https://example.test/dav/",
            username = "user",
            password = "pass",
        )
}
