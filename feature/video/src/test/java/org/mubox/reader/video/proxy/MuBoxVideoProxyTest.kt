package org.mubox.reader.video.proxy

import org.mubox.reader.core.model.media.WebDavVideoOpenRequest
import org.mubox.reader.core.model.settings.VideoForwardPrefetchMode
import org.mubox.reader.core.model.settings.VideoProxySettings
import java.util.UUID
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class MuBoxVideoProxyTest {
    @Test
    fun startCreatesAndStartsOneNativeProxyWithConfiguredLimits() = runTest {
        val native = FakeMediaProxyNative().apply { boundPort = 41_234 }
        val proxy = MuBoxVideoProxy(
            portRange = 19_000..19_010,
            requestHeaderTimeoutMillis = 1_234,
            maxRequestHeaderBytes = 4_321,
            maxRequestsPerConnection = 7,
            maxConcurrentConnections = 3,
            memoryCacheMaxBytes = 9_876L,
            native = native,
        )

        proxy.start()
        proxy.start()

        assertEquals("http://127.0.0.1:41234", proxy.baseUrl)
        assertEquals(
            listOf(
                FakeMediaProxyNative.ProxyCreateCall(
                    cacheBytes = 9_876L,
                    portStart = 19_000,
                    portEnd = 19_010,
                    headerTimeout = 1_234,
                    maxHeaderBytes = 4_321,
                    maxRequestsPerConnection = 7,
                    maxConnections = 3,
                ),
            ),
            native.proxyCreateCalls,
        )
        assertEquals(listOf(10L), native.proxyStartCalls)

        proxy.close()
    }

    @Test
    fun registerUsesUuidRouteAndPassesStreamConfigurationToNative() = runTest {
        val native = FakeMediaProxyNative()
        var clientCreates = 0
        val proxy = MuBoxVideoProxy(
            clientProvider = {
                clientCreates += 1
                error("client should stay lazy")
            },
            native = native,
        )
        proxy.start()

        val url = proxy.register(
            request = request(
                size = 42L,
                displayName = "movie #1.mkv",
                mimeType = "video/x-matroska",
            ),
            proxySettings = VideoProxySettings(
                seekOptimizationEnabled = false,
                forwardPrefetchMode = VideoForwardPrefetchMode.AGGRESSIVE,
            ),
        )

        val call = native.streamCreateCalls.single()
        assertEquals(UUID.fromString(call.routeToken).toString(), call.routeToken)
        assertEquals(call.routeToken, MuBoxVideoProxy.streamIdFromUrl(url))
        assertEquals("http://127.0.0.1:38421/stream/${call.routeToken}/movie%20%231.mkv", url)
        assertEquals(10L, call.proxy)
        assertEquals(42L, call.size)
        assertEquals("video/x-matroska", call.mime)
        assertFalse(call.seekEnabled)
        assertEquals(2, call.prefetchSegments)
        assertEquals(0, clientCreates)

        proxy.close()
    }

    @Test
    fun unregisterClosesKnownNativeStreamExactlyOnce() = runTest {
        val native = FakeMediaProxyNative()
        val proxy = MuBoxVideoProxy(native = native)
        proxy.start()
        val streamId = MuBoxVideoProxy.streamIdFromUrl(proxy.register(request()))

        assertTrue(proxy.unregister(streamId))
        assertFalse(proxy.unregister(streamId))
        assertEquals(listOf(100L), native.streamCloseCalls)
        assertNull(proxy.statistics(streamId))

        proxy.close()
        assertEquals(listOf(10L), native.proxyCloseCalls)
    }

    @Test
    fun closeClosesEveryStreamThenProxyAndIsIdempotent() = runTest {
        val native = FakeMediaProxyNative()
        val proxy = MuBoxVideoProxy(native = native)
        proxy.start()
        proxy.register(request(displayName = "one.mp4"))
        proxy.register(request(displayName = "two.mp4"))

        proxy.close()
        proxy.close()

        assertEquals(listOf(100L, 101L), native.streamCloseCalls)
        assertEquals(listOf(10L), native.proxyCloseCalls)
        assertThrows(IllegalStateException::class.java) { proxy.baseUrl }
    }

    @Test
    fun statisticsParsesSnakeCaseNativeDto() = runTest {
        val native = FakeMediaProxyNative()
        val proxy = MuBoxVideoProxy(native = native)
        proxy.start()
        val streamId = MuBoxVideoProxy.streamIdFromUrl(proxy.register(request()))
        native.statsByStream[100L] =
            """{"currentRange":"bytes=10-19","remoteHttpStatus":206,"memoryCacheHits":4,"prefetchState":"ready\nnext","diagnosticMessage":null}"""

        assertEquals(
            VideoProxyRuntimeStats(
                currentRange = "bytes=10-19",
                remoteHttpStatus = 206,
                memoryCacheHits = 4L,
                prefetchState = "ready\nnext",
                diagnosticMessage = null,
            ),
            proxy.statistics(streamId),
        )

        proxy.close()
    }

    @Test
    fun zeroNativeHandleReportsLastNativeErrorAndCleansUpManagerState() = runTest {
        val native = FakeMediaProxyNative().apply {
            failProxyCreate = true
            lastError = "bind failed"
        }
        val proxy = MuBoxVideoProxy(native = native)

        val error = runCatching { proxy.start() }.exceptionOrNull()

        assertTrue(error is MediaProxyNativeException)
        assertEquals("bind failed", error?.message)
        assertEquals(emptyList<Long>(), native.proxyCloseCalls)
    }

    private fun request(
        size: Long? = 10L,
        displayName: String = "movie.mp4",
        mimeType: String? = "video/mp4",
    ): WebDavVideoOpenRequest =
        WebDavVideoOpenRequest(
            accountId = "account-1",
            remotePath = "/movie.mp4",
            displayName = displayName,
            size = size,
            etag = null,
            lastModified = 123L,
            mimeType = mimeType,
        )
}
