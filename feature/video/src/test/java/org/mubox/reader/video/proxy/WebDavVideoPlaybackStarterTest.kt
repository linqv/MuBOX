package org.mubox.reader.video.proxy

import org.mubox.reader.core.model.settings.VideoProxySettings
import org.mubox.reader.core.model.media.WebDavVideoOpenRequest
import org.mubox.reader.core.remote.WebDavClientFactory
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WebDavVideoPlaybackStarterTest {
    private val manager = VideoProxyManager()

    @After
    fun tearDown() {
        manager.close()
    }

    @Test
    fun closesProxySessionWhenStartPlaybackFails() = runTest {
        val closedStreamIds = mutableListOf<String>()
        val session = proxySession("stream-1")

        val result = runCatching {
            startWebDavVideoPlayback(
                request = request(),
                clientFactory = clientFactory(),
                proxyManager = manager,
                openProxy = { _, _, _ -> session },
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
            clientFactory = clientFactory(),
            proxyManager = manager,
            openProxy = { _, _, _ -> session },
            closeProxy = { closedStreamIds += it },
            startPlayback = {},
        )

        assertEquals(emptyList<String>(), closedStreamIds)
    }

    @Test
    fun closesMainAndSubtitleProxyStreamsWhenStartPlaybackFails() = runTest {
        val closedStreamIds = mutableListOf<String>()
        val session = proxySession("stream-1").copy(
            subtitleUrls = listOf("http://127.0.0.1:1/stream/stream-2"),
            streamIds = listOf("stream-1", "stream-2"),
        )

        val result = runCatching {
            startWebDavVideoPlayback(
                request = request(),
                clientFactory = clientFactory(),
                proxyManager = manager,
                openProxy = { _, _, _ -> session },
                closeProxy = { closedStreamIds += it },
                startPlayback = { error("activity launch failed") },
            )
        }

        assertTrue(result.isFailure)
        assertEquals(listOf("stream-1", "stream-2"), closedStreamIds)
    }

    @Test
    fun forwardsProxySettingsWhenOpeningProxy() = runTest {
        val session = proxySession("stream-1")
        var capturedSettings: VideoProxySettings? = null
        val proxySettings = VideoProxySettings.DEFAULT.copy(seekOptimizationEnabled = false)

        startWebDavVideoPlayback(
            request = request(),
            clientFactory = clientFactory(),
            proxySettings = proxySettings,
            proxyManager = manager,
            openProxy = { _, _, settings ->
                capturedSettings = settings
                session
            },
            startPlayback = {},
        )

        assertEquals(proxySettings, capturedSettings)
    }

    private fun proxySession(streamId: String): ProxySession =
        ProxySession(
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

    private fun clientFactory(): WebDavClientFactory = WebDavClientFactory { error("not used") }
}
