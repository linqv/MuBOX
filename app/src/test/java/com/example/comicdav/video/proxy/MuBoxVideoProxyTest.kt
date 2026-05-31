package com.example.comicdav.video.proxy

import com.example.comicdav.network.ContentRange
import com.example.comicdav.network.RemoteFileInfo
import com.example.comicdav.network.WebDavClient
import com.example.comicdav.network.WebDavException
import com.example.comicdav.network.WebDavStreamResponse
import com.example.comicdav.video.WebDavVideoOpenRequest
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.PrintStream
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.URL
import java.net.ServerSocket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MuBoxVideoProxyTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var proxy: MuBoxVideoProxy? = null

    @After
    fun tearDown() {
        proxy?.close()
        scope.cancel()
    }

    @Test
    fun headReturnsKnownMetadata() = runTest {
        val url = startProxy(
            bytes = "0123456789".toByteArray(),
            size = 10L,
            mimeType = "video/mp4",
        )

        val response = httpRequest(url, method = "HEAD")

        assertEquals(200, response.code)
        assertEquals("10", response.headers["Content-Length"])
        assertEquals("video/mp4", response.headers["Content-Type"])
        assertEquals("bytes", response.headers["Accept-Ranges"])
        assertArrayEquals(ByteArray(0), response.body)
    }

    @Test
    fun registeredUrlIncludesDisplayNameExtensionForFormatDetection() = runTest {
        val localProxy = MuBoxVideoProxy(
            clientProvider = { RecordingClient("subtitle".toByteArray()) },
            coroutineScope = scope,
            portRange = 0..0,
        )
        proxy = localProxy
        localProxy.start()

        val streamUrl = localProxy.register(
            WebDavVideoOpenRequest(
                accountId = "account-1",
                remotePath = "/anime.ass",
                displayName = "anime.ass",
                size = 8L,
                etag = null,
                lastModified = null,
                mimeType = "text/x-ass",
            ),
        )

        assertTrue(streamUrl, streamUrl.endsWith("/anime.ass"))
        assertEquals(200, httpRequest(streamUrl, method = "HEAD").code)
    }

    @Test
    fun proxyBindsOnlyToLoopback() = runTest {
        startProxy(
            bytes = "0123456789".toByteArray(),
            size = 10L,
            mimeType = "video/mp4",
        )

        val socket = proxy!!.serverSocketForTest()

        assertEquals("127.0.0.1", socket.inetAddress.hostAddress)
    }

    @Test
    fun defaultPortUsesOperatingSystemAssignedPort() = runTest {
        var requestedPort: Int? = null
        val localProxy = MuBoxVideoProxy(
            clientProvider = { RecordingClient("0123456789".toByteArray()) },
            coroutineScope = scope,
            serverSocketFactory = { host, port ->
                requestedPort = port
                ServerSocket().apply {
                    bind(InetSocketAddress(InetAddress.getByName(host), port), 50)
                }
            },
        )
        proxy = localProxy

        localProxy.start()

        assertEquals(0, requestedPort)
        assertTrue(localProxy.serverSocketForTest().localPort > 0)
    }

    @Test
    fun getWithoutRangeReturnsOkAndBodyFromStart() = runTest {
        val client = RecordingClient("0123456789".toByteArray())
        val url = startProxy(client = client, size = 10L)

        val response = httpRequest(url, method = "GET")

        assertEquals(200, response.code)
        assertEquals("10", response.headers["Content-Length"])
        assertNull(response.headers["Content-Range"])
        assertEquals(listOf("/video.mp4"), client.fullStreamCalls)
        assertEquals(emptyList<Pair<Long, Long?>>(), client.openRangeCalls)
        assertArrayEquals("0123456789".toByteArray(), response.body)
    }

    @Test
    fun getWithRangeReturnsPartialContent() = runTest {
        val client = RecordingClient("0123456789".toByteArray())
        val url = startProxy(client = client, size = 10L)

        val response = httpRequest(url, method = "GET", range = "bytes=2-4")

        assertEquals(206, response.code)
        assertEquals("3", response.headers["Content-Length"])
        assertEquals("bytes 2-4/10", response.headers["Content-Range"])
        assertEquals(2L to 4L, client.openRangeCalls.first())
        assertArrayEquals("234".toByteArray(), response.body)
    }

    @Test
    fun sameSocketServesSequentialRangeRequests() = runTest {
        val client = RecordingClient("0123456789".toByteArray())
        val url = startProxy(client = client, size = 10L)
        val parsed = URL(url)

        Socket(parsed.host, parsed.port).use { socket ->
            socket.soTimeout = 2_000
            val exchange = RawHttpExchange(socket)

            exchange.writeRequest(
                buildRequest(parsed, method = "GET", range = "bytes=0-2"),
            )
            val first = exchange.readResponse()
            exchange.writeRequest(
                buildRequest(parsed, method = "GET", range = "bytes=3-5"),
            )
            val second = exchange.readResponse()

            assertEquals(206, first.code)
            assertEquals("keep-alive", first.header("Connection"))
            assertArrayEquals("012".toByteArray(), first.body)
            assertEquals(206, second.code)
            assertArrayEquals("345".toByteArray(), second.body)
        }
    }

    @Test
    fun connectionCloseResponseClosesSocketAfterBody() = runTest {
        val url = startProxy(client = RecordingClient("0123456789".toByteArray()), size = 10L)
        val parsed = URL(url)

        Socket(parsed.host, parsed.port).use { socket ->
            socket.soTimeout = 2_000
            val exchange = RawHttpExchange(socket)

            exchange.writeRequest(
                buildRequest(parsed, method = "GET", range = "bytes=0-2", connection = "close"),
            )
            val response = exchange.readResponse()

            assertEquals(206, response.code)
            assertEquals("close", response.header("Connection"))
            assertArrayEquals("012".toByteArray(), response.body)
            exchange.assertClosed()
        }
    }

    @Test
    fun fullBodyGetWithKnownLengthCanBeFollowedByRangeOnSameSocket() = runTest {
        val url = startProxy(client = RecordingClient("0123456789".toByteArray()), size = 10L)
        val parsed = URL(url)

        Socket(parsed.host, parsed.port).use { socket ->
            socket.soTimeout = 2_000
            val exchange = RawHttpExchange(socket)

            exchange.writeRequest(buildRequest(parsed, method = "GET"))
            val full = exchange.readResponse()
            exchange.writeRequest(buildRequest(parsed, method = "GET", range = "bytes=8-9"))
            val range = exchange.readResponse()

            assertEquals(200, full.code)
            assertEquals("10", full.header("Content-Length"))
            assertEquals("keep-alive", full.header("Connection"))
            assertArrayEquals("0123456789".toByteArray(), full.body)
            assertEquals(206, range.code)
            assertArrayEquals("89".toByteArray(), range.body)
        }
    }

    @Test
    fun rangeResponseWithLongerUpstreamBodyClosesSocketAfterDeclaredLength() = runTest {
        val url = startProxy(client = MismatchedRangeBodyClient("0123456789".toByteArray()), size = 10L)
        val parsed = URL(url)

        Socket(parsed.host, parsed.port).use { socket ->
            socket.soTimeout = 2_000
            val exchange = RawHttpExchange(socket)

            exchange.writeRequest(buildRequest(parsed, method = "GET", range = "bytes=2-4"))
            val response = exchange.readResponse()

            assertEquals(206, response.code)
            assertEquals("3", response.header("Content-Length"))
            assertEquals(3, response.body.size)
            exchange.assertClosed()
        }
    }

    @Test
    fun rangeResponseWithShorterUpstreamBodyClosesSocket() = runTest {
        val url = startProxy(client = MismatchedRangeBodyClient("01".toByteArray()), size = 10L)
        val parsed = URL(url)

        Socket(parsed.host, parsed.port).use { socket ->
            socket.soTimeout = 2_000
            val exchange = RawHttpExchange(socket)

            exchange.writeRequest(buildRequest(parsed, method = "GET", range = "bytes=0-2"))
            val response = exchange.readResponseUntilClosed()

            assertEquals(206, response.code)
            assertEquals("3", response.header("Content-Length"))
            assertArrayEquals("01".toByteArray(), response.body)
        }
    }

    @Test
    fun headCanBeFollowedByGetOnSameSocket() = runTest {
        val url = startProxy(client = RecordingClient("0123456789".toByteArray()), size = 10L)
        val parsed = URL(url)

        Socket(parsed.host, parsed.port).use { socket ->
            socket.soTimeout = 2_000
            val exchange = RawHttpExchange(socket)

            exchange.writeRequest(buildRequest(parsed, method = "HEAD"))
            val head = exchange.readResponse(expectBody = false)
            exchange.writeRequest(buildRequest(parsed, method = "GET", range = "bytes=6-9"))
            val get = exchange.readResponse()

            assertEquals(200, head.code)
            assertEquals("10", head.header("Content-Length"))
            assertEquals("keep-alive", head.header("Connection"))
            assertArrayEquals(ByteArray(0), head.body)
            assertEquals(206, get.code)
            assertArrayEquals("6789".toByteArray(), get.body)
        }
    }

    @Test
    fun http10KeepsAliveOnlyWhenRequested() = runTest {
        val url = startProxy(client = RecordingClient("0123456789".toByteArray()), size = 10L)
        val parsed = URL(url)

        Socket(parsed.host, parsed.port).use { socket ->
            socket.soTimeout = 2_000
            val exchange = RawHttpExchange(socket)

            exchange.writeRequest(
                buildRequest(parsed, method = "GET", version = "HTTP/1.0", range = "bytes=0-1", connection = "keep-alive"),
            )
            val first = exchange.readResponse()
            exchange.writeRequest(
                buildRequest(parsed, method = "GET", version = "HTTP/1.0", range = "bytes=2-3", connection = "close"),
            )
            val second = exchange.readResponse()

            assertEquals(206, first.code)
            assertEquals("keep-alive", first.header("Connection"))
            assertArrayEquals("01".toByteArray(), first.body)
            assertEquals(206, second.code)
            assertEquals("close", second.header("Connection"))
            assertArrayEquals("23".toByteArray(), second.body)
            exchange.assertClosed()
        }
    }

    @Test
    fun repeatedRangeRequestUsesCacheWhenSeekOptimizationIsEnabled() = runTest {
        val client = RecordingClient("0123456789".toByteArray())
        val url = startProxy(
            client = client,
            size = 10L,
            proxySettings = VideoProxySettings.DEFAULT.copy(
                seekOptimizationEnabled = true,
                forwardPrefetchMode = VideoForwardPrefetchMode.OFF,
            ),
        )

        assertArrayEquals("012".toByteArray(), httpRequest(url, method = "GET", range = "bytes=0-2").body)
        eventually { assertTrue(client.openRangeCalls.contains(0L to 9L)) }
        assertArrayEquals("123".toByteArray(), httpRequest(url, method = "GET", range = "bytes=1-3").body)

        assertEquals(listOf(0L to 2L, 0L to 9L), client.openRangeCalls)
    }

    @Test
    fun rangeRequestBypassesOptimizerWhenSeekOptimizationIsDisabled() = runTest {
        val client = RecordingClient("0123456789".toByteArray())
        val url = startProxy(
            client = client,
            size = 10L,
            proxySettings = VideoProxySettings.DEFAULT.copy(seekOptimizationEnabled = false),
        )

        httpRequest(url, method = "GET", range = "bytes=0-2")
        httpRequest(url, method = "GET", range = "bytes=1-3")

        assertEquals(listOf(0L to 2L, 1L to 3L), client.openRangeCalls)
    }

    @Test
    fun registeredStreamReusesOpenedWebDavClientAcrossRequests() = runTest {
        val client = RecordingClient("0123456789".toByteArray())
        var openCount = 0
        val localProxy = MuBoxVideoProxy(
            clientProvider = {
                openCount += 1
                client
            },
            coroutineScope = scope,
            portRange = 0..0,
        )
        proxy = localProxy
        localProxy.start()
        val streamUrl = localProxy.register(
            WebDavVideoOpenRequest(
                accountId = "account-1",
                remotePath = "/video.mp4",
                displayName = "video.mp4",
                size = 10L,
                etag = null,
                lastModified = null,
                mimeType = "video/mp4",
            ),
            proxySettings = VideoProxySettings.DEFAULT.copy(seekOptimizationEnabled = false),
        )

        httpRequest(streamUrl, method = "GET", range = "bytes=0-2")
        httpRequest(streamUrl, method = "GET", range = "bytes=3-4")

        assertEquals(1, openCount)
    }

    @Test
    fun explicitRangeLargerThanSegmentUsesDirectRemoteRange() = runTest {
        val maxOptimizedBytes = VideoRangeMemoryCache.DEFAULT_SEGMENT_BYTES
        val bytes = ByteArray((maxOptimizedBytes + 2L).toInt()) { (it % 251).toByte() }
        val client = RecordingClient(bytes)
        val url = startProxy(client = client, size = bytes.size.toLong())

        val response = httpRequest(url, method = "GET", range = "bytes=0-$maxOptimizedBytes")

        assertEquals(206, response.code)
        assertEquals(maxOptimizedBytes + 1L, response.body.size.toLong())
        assertEquals(listOf(0L to maxOptimizedBytes), client.openRangeCalls)
    }

    @Test
    fun explicitRangeCrossingSegmentBoundaryUsesDirectRemoteRange() = runTest {
        val segmentBytes = VideoRangeMemoryCache.DEFAULT_SEGMENT_BYTES
        val bytes = ByteArray((segmentBytes + 8L).toInt()) { (it % 251).toByte() }
        val client = RecordingClient(bytes)
        val url = startProxy(client = client, size = bytes.size.toLong())

        val response = httpRequest(url, method = "GET", range = "bytes=${segmentBytes - 4}-${segmentBytes + 3}")

        assertEquals(206, response.code)
        assertArrayEquals(bytes.copyOfRange((segmentBytes - 4).toInt(), (segmentBytes + 4).toInt()), response.body)
        assertEquals(listOf(segmentBytes - 4 to segmentBytes + 3), client.openRangeCalls)
    }

    @Test
    fun optimizedRangeFailureFallsBackToDirectRange() = runTest {
        val bytes = ByteArray((256 * 1024) + 2) { (it % 251).toByte() }
        val client = OptimizedRangeFailingClient(bytes)
        val url = startProxy(client = client, size = bytes.size.toLong())

        val response = httpRequest(url, method = "GET", range = "bytes=0-${256 * 1024}")

        assertEquals(206, response.code)
        assertEquals("bytes 0-${256 * 1024}/${bytes.size}", response.headers["Content-Range"])
        assertArrayEquals(bytes.copyOfRange(0, (256 * 1024) + 1), response.body)
        assertEquals(listOf(0L to bytes.lastIndex.toLong(), 0L to 256L * 1024L), client.openRangeCalls)
    }

    @Test
    fun optimizedRangeFailureEmitsSummaryDiagnosticFallback() = runTest {
        val originalErr = System.err
        val errorBytes = ByteArrayOutputStream()
        System.setErr(PrintStream(errorBytes))
        try {
            val bytes = ByteArray((256 * 1024) + 2) { (it % 251).toByte() }
            val client = OptimizedRangeFailingClient(bytes)
            val url = startProxy(
                client = client,
                size = bytes.size.toLong(),
                proxySettings = VideoProxySettings.DEFAULT.copy(
                    diagnosticsMode = VideoProxyDiagnosticsMode.SUMMARY,
                ),
            )

            val response = httpRequest(url, method = "GET", range = "bytes=0-${256 * 1024}")

            assertEquals(206, response.code)
        } finally {
            System.setErr(originalErr)
        }
        val logs = errorBytes.toString(Charsets.UTF_8.name())
        assertTrue(logs.contains("video_proxy fallback"))
        assertFalse(logs.contains("/video.mp4"))
    }

    @Test
    fun getWithLfOnlyHeadersReturnsPartialContent() = runTest {
        val client = RecordingClient("0123456789".toByteArray())
        val localProxy = MuBoxVideoProxy(
            clientProvider = { client },
            coroutineScope = scope,
            portRange = 0..0,
            requestHeaderTimeoutMillis = 100,
        )
        proxy = localProxy
        localProxy.start()
        val streamUrl = localProxy.register(
            WebDavVideoOpenRequest(
                accountId = "account-1",
                remotePath = "/video.mp4",
                displayName = "video.mp4",
                size = 10L,
                etag = null,
                lastModified = null,
                mimeType = "video/mp4",
            ),
        )
        val parsed = URL(streamUrl)

        val response = Socket(parsed.host, parsed.port).use { socket ->
            socket.soTimeout = 1_000
            socket.getOutputStream().write(
                buildString {
                    append("GET ${parsed.path} HTTP/1.1\n")
                    append("Host: ${parsed.host}:${parsed.port}\n")
                    append("Range: bytes=2-4\n")
                    append("\n")
                }.toByteArray(),
            )
            socket.getOutputStream().flush()
            socket.getInputStream().readBytes()
        }

        val headerBlock = response.toString(Charsets.ISO_8859_1).substringBefore("\r\n\r\n")
        assertTrue(headerBlock.startsWith("HTTP/1.1 206 Partial Content"))
        val body = response.copyOfRange(response.size - 3, response.size)
        assertEquals(2L to 4L, client.openRangeCalls.first())
        assertArrayEquals("234".toByteArray(), body)
    }

    @Test
    fun openEndedRangeIsBoundedBeforeRemoteRequest() = runTest {
        val client = RecordingClient("0123456789".toByteArray())
        val url = startProxy(client = client, size = 10L)

        val response = httpRequest(url, method = "GET", range = "bytes=2-")

        assertEquals(206, response.code)
        assertEquals("bytes 2-9/10", response.headers["Content-Range"])
        assertEquals(2L to 9L, client.openRangeCalls.first())
        assertArrayEquals("23456789".toByteArray(), response.body)
    }

    @Test
    fun openEndedRangeWarmsCacheAndUsesCachedSegmentsOnLaterOpenEndedRequest() = runTest {
        val segmentBytes = VideoRangeMemoryCache.DEFAULT_SEGMENT_BYTES.toInt()
        val bytes = ByteArray(segmentBytes + 8) { (it % 251).toByte() }
        val client = RecordingClient(bytes)
        val url = startProxy(
            client = client,
            size = bytes.size.toLong(),
            proxySettings = VideoProxySettings.DEFAULT.copy(forwardPrefetchMode = VideoForwardPrefetchMode.OFF),
        )

        val first = httpRequest(url, method = "GET", range = "bytes=0-")
        val second = httpRequest(url, method = "GET", range = "bytes=${segmentBytes - 4}-")

        assertEquals(206, first.code)
        assertEquals(206, second.code)
        assertArrayEquals(bytes, first.body)
        assertArrayEquals(bytes.copyOfRange(segmentBytes - 4, bytes.size), second.body)
        assertEquals(
            listOf(
                0L to bytes.lastIndex.toLong(),
            ),
            client.openRangeCalls,
        )
    }

    @Test
    fun openEndedRangePrefetchesNextLocalChunkForSequentialPlayback() = runTest {
        val segmentBytes = VideoRangeMemoryCache.DEFAULT_SEGMENT_BYTES.toInt()
        val chunkBytes = segmentBytes * 4
        val bytes = ByteArray(chunkBytes * 3) { (it % 251).toByte() }
        val client = RecordingClient(bytes)
        val url = startProxy(
            client = client,
            size = bytes.size.toLong(),
            proxySettings = VideoProxySettings.DEFAULT.copy(forwardPrefetchMode = VideoForwardPrefetchMode.STANDARD),
        )
        fun segmentRange(index: Int): Pair<Long, Long?> {
            val start = index.toLong() * segmentBytes.toLong()
            val end = (start + segmentBytes.toLong() - 1L).coerceAtMost(bytes.lastIndex.toLong())
            return start to end
        }
        val expectedAfterFirst = listOf(0L to chunkBytes.toLong() - 1L) +
            (4 until 8).map(::segmentRange)
        val expectedAfterSecond = expectedAfterFirst + (8 until 12).map(::segmentRange)

        val first = httpRequest(url, method = "GET", range = "bytes=0-")
        eventually {
            assertEquals(expectedAfterFirst, client.openRangeCalls)
        }

        val second = httpRequest(url, method = "GET", range = "bytes=$chunkBytes-")
        eventually {
            assertEquals(expectedAfterSecond, client.openRangeCalls)
        }
        val third = httpRequest(url, method = "GET", range = "bytes=${chunkBytes * 2}-")

        assertEquals(206, first.code)
        assertEquals(206, second.code)
        assertEquals(206, third.code)
        assertArrayEquals(bytes.copyOfRange(0, chunkBytes), first.body)
        assertArrayEquals(bytes.copyOfRange(chunkBytes, chunkBytes * 2), second.body)
        assertArrayEquals(bytes.copyOfRange(chunkBytes * 2, bytes.size), third.body)
        assertFalse(client.openRangeCalls.contains(chunkBytes.toLong() to chunkBytes.toLong() * 2L - 1L))
        assertFalse(client.openRangeCalls.contains(chunkBytes.toLong() * 2L to bytes.lastIndex.toLong()))
    }

    @Test
    fun openEndedRangeSendsInitialBodyBytesBeforeLaterRemoteBytesFinish() = runTest {
        val segmentBytes = VideoRangeMemoryCache.DEFAULT_SEGMENT_BYTES.toInt()
        val bytes = ByteArray(segmentBytes + 8) { (it % 251).toByte() }
        val client = SlowAfterFirstByteRangeClient(bytes)
        val url = startProxy(
            client = client,
            size = bytes.size.toLong(),
            proxySettings = VideoProxySettings.DEFAULT.copy(forwardPrefetchMode = VideoForwardPrefetchMode.OFF),
        )
        val parsed = URL(url)

        try {
            Socket(parsed.host, parsed.port).use { socket ->
                socket.soTimeout = 500
                socket.getOutputStream().write(
                    buildString {
                        append("GET ${parsed.path} HTTP/1.1\r\n")
                        append("Host: ${parsed.host}:${parsed.port}\r\n")
                        append("Range: bytes=0-\r\n")
                        append("Connection: close\r\n")
                        append("\r\n")
                    }.toByteArray(),
                )
                socket.getOutputStream().flush()

                val headerAndFirstBodyByte = readHeadersAndFirstBodyByte(socket)

                assertTrue(headerAndFirstBodyByte.startsWith("HTTP/1.1 206 Partial Content"))
                assertEquals(0, headerAndFirstBodyByte.encodeToByteArray().last().toInt() and 0xff)
            }
        } finally {
            client.release()
        }
    }

    @Test
    fun suffixRangeReturnsTailBytes() = runTest {
        val client = RecordingClient("0123456789".toByteArray())
        val url = startProxy(client = client, size = 10L)

        val response = httpRequest(url, method = "GET", range = "bytes=-4")

        assertEquals(206, response.code)
        assertEquals("bytes 6-9/10", response.headers["Content-Range"])
        assertEquals(listOf(6L to 9L), client.openRangeCalls)
        assertArrayEquals("6789".toByteArray(), response.body)
    }

    @Test
    fun invalidRangeReturnsRangeNotSatisfiable() = runTest {
        val client = RecordingClient("0123456789".toByteArray())
        val url = startProxy(client = client, size = 10L)

        val response = httpRequest(url, method = "GET", range = "bytes=20-30")

        assertEquals(416, response.code)
        assertEquals("bytes */10", response.headers["Content-Range"])
        assertFalse(client.openRangeCalls.isNotEmpty())
    }

    @Test
    fun rangeNotSatisfiableClosesConnection() = runTest {
        val url = startProxy(client = RecordingClient("0123456789".toByteArray()), size = 10L)
        val parsed = URL(url)

        Socket(parsed.host, parsed.port).use { socket ->
            socket.soTimeout = 2_000
            val exchange = RawHttpExchange(socket)

            exchange.writeRequest(buildRequest(parsed, method = "GET", range = "bytes=20-30"))
            val response = exchange.readResponse()

            assertEquals(416, response.code)
            assertEquals("close", response.header("Connection"))
            exchange.assertClosed()
        }
    }

    @Test
    fun registerGeneratesUnpredictableNonSequentialStreamIds() = runTest {
        val client = RecordingClient("0123456789".toByteArray())
        val firstUrl = startProxy(client = client, size = 10L)
        val secondUrl = proxy!!.register(
            WebDavVideoOpenRequest(
                accountId = "account-1",
                remotePath = "/video.mp4",
                displayName = "video.mp4",
                size = 10L,
                etag = null,
                lastModified = null,
                mimeType = "video/mp4",
            ),
        )

        val firstId = streamIdFromUrl(firstUrl)
        val secondId = streamIdFromUrl(secondUrl)
        assertTrue(firstId != secondId)
        assertTrue(firstId != "1" && secondId != "2")
        assertEquals(36, firstId.length)
    }

    @Test
    fun unregisteredStreamReturnsNotFound() = runTest {
        val client = RecordingClient("0123456789".toByteArray())
        val streamUrl = startProxy(client = client, size = 10L)
        val streamId = streamIdFromUrl(streamUrl)
        proxy?.unregister(streamId)

        val response = httpRequest(streamUrl, method = "GET")

        assertEquals(404, response.code)
    }

    @Test
    fun unregisterClosesActiveStreamResponse() = runTest {
        val input = BlockingInputStream()
        val client = BlockingStreamClient(input)
        val streamUrl = startProxy(client = client, size = 10L)
        val streamId = streamIdFromUrl(streamUrl)
        val requestThread = thread(start = true) {
            runCatching {
                httpRequest(streamUrl, method = "GET", range = "bytes=0-")
            }
        }
        assertTrue(input.readStarted.await(2, TimeUnit.SECONDS))

        proxy?.unregister(streamId)
        val closedByUnregister = input.closed.await(500, TimeUnit.MILLISECONDS)
        input.forceClose()
        requestThread.join(1_000)

        assertTrue("unregister should close active stream response", closedByUnregister)
    }

    @Test
    fun unregisterCancelsOpeningDirectRangeRequest() = runTest {
        val client = CancellableOpeningRangeClient()
        val streamUrl = startProxy(
            client = client,
            size = 10L,
            proxySettings = VideoProxySettings.DEFAULT.copy(seekOptimizationEnabled = false),
        )
        val streamId = streamIdFromUrl(streamUrl)
        val requestThread = thread(start = true) {
            runCatching {
                httpRequest(streamUrl, method = "GET", range = "bytes=0-2")
            }
        }
        assertTrue(client.openStarted.await(2, TimeUnit.SECONDS))

        proxy?.unregister(streamId)
        val cancelled = client.cancelled.await(500, TimeUnit.MILLISECONDS)
        client.release()
        requestThread.join(1_000)

        assertTrue("unregister should cancel the in-flight WebDAV call before response headers", cancelled)
        assertTrue("proxy should use the cancellable WebDavClient overload", client.cancellableOverloadUsed.get())
    }

    @Test
    fun clientDisconnectDuringRangeStreamDoesNotBreakProxy() = runTest {
        val exceptionCount = AtomicInteger(0)
        val localScope = CoroutineScope(
            SupervisorJob() +
                Dispatchers.IO +
                CoroutineExceptionHandler { _, _ -> exceptionCount.incrementAndGet() },
        )
        val localProxy = MuBoxVideoProxy(
            clientProvider = { RecordingClient(ByteArray(256 * 1024) { (it % 251).toByte() }) },
            coroutineScope = localScope,
            portRange = 0..0,
        )
        try {
            localProxy.start()
            val streamUrl = localProxy.register(
                WebDavVideoOpenRequest(
                    accountId = "account-1",
                    remotePath = "/video.mp4",
                    displayName = "video.mp4",
                    size = 256L * 1024L,
                    etag = null,
                    lastModified = null,
                    mimeType = "video/mp4",
                ),
            )
            abortStreamingRequest(streamUrl)
            Thread.sleep(200)

            val response = httpRequest(streamUrl, method = "GET", range = "bytes=0-3")

            assertEquals(0, exceptionCount.get())
            assertEquals(206, response.code)
            assertEquals("bytes 0-3/262144", response.headers["Content-Range"])
        } finally {
            localProxy.close()
            localScope.cancel()
        }
    }

    @Test
    fun remoteHeadFailureReturnsBadGateway() = runTest {
        val client = FailingClient(headError = WebDavException.MissingMetadata("missing size"))
        val url = startProxy(client = client, size = null)

        val response = httpRequest(url, method = "HEAD")

        assertEquals(502, response.code)
    }

    @Test
    fun remoteStreamFailureReturnsBadGateway() = runTest {
        val client = FailingClient(streamError = WebDavException.InvalidContentRange("bad range"))
        val url = startProxy(client = client, size = 10L)

        val response = httpRequest(url, method = "GET", range = "bytes=0-2")

        assertEquals(502, response.code)
    }

    @Test
    fun remoteStreamFailureClosesConnection() = runTest {
        val client = FailingClient(streamError = WebDavException.InvalidContentRange("bad range"))
        val url = startProxy(client = client, size = 10L)
        val parsed = URL(url)

        Socket(parsed.host, parsed.port).use { socket ->
            socket.soTimeout = 2_000
            val exchange = RawHttpExchange(socket)

            exchange.writeRequest(buildRequest(parsed, method = "GET", range = "bytes=0-2"))
            val response = exchange.readResponse()

            assertEquals(502, response.code)
            assertEquals("close", response.header("Connection"))
            exchange.assertClosed()
        }
    }

    @Test
    fun openEndedRemoteStreamFailureDoesNotRetryDirectRange() = runTest {
        val client = FailingClient(streamError = WebDavException.InvalidContentRange("bad range"))
        val url = startProxy(client = client, size = 10L)

        val response = httpRequest(url, method = "GET", range = "bytes=0-")

        assertEquals(502, response.code)
        assertEquals(1, client.openRangeCallCount.get())
    }

    @Test
    fun proxyFailureLogRedactsSensitiveDetails() = runTest {
        val originalErr = System.err
        val errorBytes = ByteArrayOutputStream()
        System.setErr(PrintStream(errorBytes))
        try {
            val client = FailingClient(
                streamError = WebDavException.Network(
                    "failed https://user:pass@example.test/video.mp4?token=abc Authorization: Bearer secret",
                ),
            )
            val url = startProxy(
                client = client,
                size = 10L,
                remotePath = "/private/password-hunter2/movie.mp4",
            )

            val response = httpRequest(url, method = "GET", range = "bytes=0-2")

            assertEquals(502, response.code)
        } finally {
            System.setErr(originalErr)
        }
        val logs = errorBytes.toString(Charsets.UTF_8.name())
        assertFalse(logs.contains("/private/password-hunter2/movie.mp4"))
        assertFalse(logs.contains("user:pass"))
        assertFalse(logs.contains("token=abc"))
        assertFalse(logs.contains("Bearer secret"))
        assertTrue(logs.contains("<redacted>"))
    }

    @Test
    fun responseContentRangeWithUnknownTotalFallsBackToKnownSize() = runTest {
        val client = UnknownTotalRangeClient()
        val url = startProxy(client = client, size = 10L)

        val response = httpRequest(url, method = "GET", range = "bytes=2-4")

        assertEquals(206, response.code)
        assertEquals("bytes 2-4/10", response.headers["Content-Range"])
        assertArrayEquals("234".toByteArray(), response.body)
    }

    @Test
    fun concurrentStartBindsOnlyOneServerSocket() = runTest {
        val boundSockets = CopyOnWriteArrayList<ServerSocket>()
        val localProxy = MuBoxVideoProxy(
            clientProvider = { RecordingClient("0123456789".toByteArray()) },
            coroutineScope = scope,
            portRange = 0..0,
            serverSocketFactory = { host, port ->
                Thread.sleep(50)
                ServerSocket().apply {
                    bind(InetSocketAddress(InetAddress.getByName(host), port), 50)
                    boundSockets += this
                }
            },
        )
        proxy = localProxy

        coroutineScope {
            List(16) {
                async(Dispatchers.IO) { localProxy.start() }
            }.awaitAll()
        }

        assertEquals(1, boundSockets.size)
    }

    @Test
    fun oversizedRequestHeaderReturnsRequestHeaderFieldsTooLarge() = runTest {
        val url = startProxy(client = RecordingClient("0123456789".toByteArray()), size = 10L)
        val parsed = URL(url)

        val response = Socket(parsed.host, parsed.port).use { socket ->
            socket.soTimeout = 2_000
            socket.getOutputStream().write(
                buildString {
                    append("GET ${parsed.path} HTTP/1.1\r\n")
                    append("Host: ${parsed.host}:${parsed.port}\r\n")
                    append("X-Fill: ")
                    append("x".repeat(70 * 1024))
                    append("\r\n\r\n")
                }.toByteArray(),
            )
            socket.getOutputStream().flush()
            socket.getInputStream().bufferedReader().readLine()
        }

        assertEquals("HTTP/1.1 431 Request Header Fields Too Large", response)
    }

    @Test
    fun oversizedRequestHeaderClosesConnection() = runTest {
        val url = startProxy(client = RecordingClient("0123456789".toByteArray()), size = 10L)
        val parsed = URL(url)

        Socket(parsed.host, parsed.port).use { socket ->
            socket.soTimeout = 2_000
            val exchange = RawHttpExchange(socket)

            exchange.writeRequest(
                buildString {
                    append("GET ${parsed.path} HTTP/1.1\r\n")
                    append("Host: ${parsed.host}:${parsed.port}\r\n")
                    append("X-Fill: ")
                    append("x".repeat(70 * 1024))
                    append("\r\n\r\n")
                },
            )
            val response = exchange.readResponse()

            assertEquals(431, response.code)
            assertEquals("close", response.header("Connection"))
            exchange.assertClosed()
        }
    }

    @Test
    fun idleHeaderConnectionTimesOutAndProxyStaysUsable() = runTest {
        val localProxy = MuBoxVideoProxy(
            clientProvider = { RecordingClient("0123456789".toByteArray()) },
            coroutineScope = scope,
            portRange = 0..0,
            requestHeaderTimeoutMillis = 100,
        )
        proxy = localProxy
        localProxy.start()
        val streamUrl = localProxy.register(
            WebDavVideoOpenRequest(
                accountId = "account-1",
                remotePath = "/video.mp4",
                displayName = "video.mp4",
                size = 10L,
                etag = null,
                lastModified = null,
                mimeType = "video/mp4",
            ),
        )
        val parsed = URL(streamUrl)

        val timedOut = Socket(parsed.host, parsed.port).use { socket ->
            socket.soTimeout = 1_000
            socket.getInputStream().read() == -1
        }
        val response = httpRequest(streamUrl, method = "GET", range = "bytes=0-2")

        assertTrue(timedOut)
        assertEquals(206, response.code)
        assertArrayEquals("012".toByteArray(), response.body)
    }

    @Test
    fun transientAcceptFailureDoesNotStopProxy() = runTest {
        val localProxy = MuBoxVideoProxy(
            clientProvider = { RecordingClient("0123456789".toByteArray()) },
            coroutineScope = scope,
            portRange = 0..0,
            serverSocketFactory = { host, port ->
                FlakyAcceptServerSocket(
                    InetSocketAddress(InetAddress.getByName(host), port),
                )
            },
        )
        proxy = localProxy
        localProxy.start()
        val streamUrl = localProxy.register(
            WebDavVideoOpenRequest(
                accountId = "account-1",
                remotePath = "/video.mp4",
                displayName = "video.mp4",
                size = 10L,
                etag = null,
                lastModified = null,
                mimeType = "video/mp4",
            ),
        )

        val response = httpRequest(streamUrl, method = "GET", range = "bytes=0-2")

        assertEquals(206, response.code)
        assertArrayEquals("012".toByteArray(), response.body)
    }

    @Test
    fun statisticsTrackRangeRemoteStatusCacheHitsAndPrefetchState() = runTest {
        val bytes = ByteArray((VideoRangeMemoryCache.DEFAULT_SEGMENT_BYTES * 3L).toInt()) { (it % 251).toByte() }
        val client = RecordingClient(bytes)
        val streamUrl = startProxy(
            client = client,
            size = bytes.size.toLong(),
            proxySettings = VideoProxySettings.DEFAULT.copy(forwardPrefetchMode = VideoForwardPrefetchMode.STANDARD),
        )
        val streamId = streamIdFromUrl(streamUrl)

        httpRequest(streamUrl, method = "GET", range = "bytes=0-${256 * 1024}")
        eventually {
            val stats = proxy!!.statistics(streamId) ?: error("missing proxy statistics")
            assertEquals("bytes=0-262144", stats.currentRange)
            assertEquals(206, stats.remoteHttpStatus)
            assertTrue("prefetch state should be reported", stats.prefetchState?.isNotBlank() == true)
        }
        httpRequest(streamUrl, method = "GET", range = "bytes=1-3")

        val stats = proxy!!.statistics(streamId) ?: error("missing proxy statistics")
        assertTrue("cache hits should be counted after overlapping optimized request", stats.memoryCacheHits > 0L)
    }

    private suspend fun startProxy(
        bytes: ByteArray,
        size: Long,
        mimeType: String = "video/mp4",
        proxySettings: VideoProxySettings = VideoProxySettings.DEFAULT,
    ): String = startProxy(
        client = RecordingClient(bytes),
        size = size,
        mimeType = mimeType,
        proxySettings = proxySettings,
    )

    private suspend fun startProxy(
        client: WebDavClient,
        size: Long?,
        mimeType: String = "video/mp4",
        proxySettings: VideoProxySettings = VideoProxySettings.DEFAULT,
        remotePath: String = "/video.mp4",
    ): String {
        val localProxy = MuBoxVideoProxy(
            clientProvider = { client },
            coroutineScope = scope,
            portRange = 0..0,
        )
        proxy = localProxy
        localProxy.start()
        return localProxy.register(
            WebDavVideoOpenRequest(
                accountId = "account-1",
                remotePath = remotePath,
                displayName = "video.mp4",
                size = size,
                etag = null,
                lastModified = null,
                mimeType = mimeType,
            ),
            proxySettings = proxySettings,
        )
    }

    private fun MuBoxVideoProxy.serverSocketForTest(): ServerSocket {
        val field = MuBoxVideoProxy::class.java.getDeclaredField("serverSocket")
        field.isAccessible = true
        return field.get(this) as ServerSocket
    }

    private fun streamIdFromUrl(url: String): String =
        URL(url).path.removePrefix("/stream/").substringBefore('/')

    private fun buildRequest(
        parsed: URL,
        method: String,
        version: String = "HTTP/1.1",
        range: String? = null,
        connection: String? = null,
    ): String = buildString {
        append("$method ${parsed.path} $version\r\n")
        append("Host: ${parsed.host}:${parsed.port}\r\n")
        range?.let { append("Range: $it\r\n") }
        connection?.let { append("Connection: $it\r\n") }
        append("\r\n")
    }

    private fun httpRequest(url: String, method: String, range: String? = null): HttpResponse {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 2_000
            readTimeout = 2_000
            range?.let { setRequestProperty("Range", it) }
        }
        val code = connection.responseCode
        val body = if (method == "HEAD") {
            ByteArray(0)
        } else {
            (if (code >= 400) connection.errorStream else connection.inputStream)?.readBytes() ?: ByteArray(0)
        }
        val headers = connection.headerFields
            .filterKeys { it != null }
            .mapKeys { it.key!! }
            .mapValues { it.value.firstOrNull().orEmpty() }
        connection.disconnect()
        return HttpResponse(code, headers, body)
    }

    private fun abortStreamingRequest(url: String) {
        val parsed = URL(url)
        Socket(parsed.host, parsed.port).use { socket ->
            socket.setSoLinger(true, 0)
            socket.getOutputStream().write(
                buildString {
                    append("GET ${parsed.path} HTTP/1.1\r\n")
                    append("Host: ${parsed.host}:${parsed.port}\r\n")
                    append("Range: bytes=0-\r\n")
                    append("Connection: close\r\n")
                    append("\r\n")
                }.toByteArray(),
            )
            socket.getOutputStream().flush()
            val buffer = ByteArray(256)
            socket.getInputStream().read(buffer)
        }
    }

    private suspend fun eventually(assertion: () -> Unit) {
        val deadline = System.currentTimeMillis() + 2_000
        var lastError: AssertionError? = null
        while (System.currentTimeMillis() < deadline) {
            try {
                assertion()
                return
            } catch (error: AssertionError) {
                lastError = error
                delay(20)
            }
        }
        throw lastError ?: AssertionError("condition was not met")
    }

    private fun readHeadersAndFirstBodyByte(socket: Socket): String {
        val buffer = ByteArrayOutputStream()
        val terminator = "\r\n\r\n".toByteArray(Charsets.ISO_8859_1)
        while (true) {
            val next = try {
                socket.getInputStream().read()
            } catch (error: SocketTimeoutException) {
                throw AssertionError("timed out waiting for initial proxy response bytes", error)
            }
            if (next == -1) {
                throw AssertionError("connection closed before body bytes were received")
            }
            buffer.write(next)
            val bytes = buffer.toByteArray()
            val headerEnd = bytes.indexOf(terminator)
            if (headerEnd >= 0 && bytes.size > headerEnd + terminator.size) {
                return bytes.toString(Charsets.ISO_8859_1)
            }
        }
    }

    private fun ByteArray.indexOf(pattern: ByteArray): Int {
        if (pattern.isEmpty() || size < pattern.size) return -1
        for (index in 0..size - pattern.size) {
            var matched = true
            for (patternIndex in pattern.indices) {
                if (this[index + patternIndex] != pattern[patternIndex]) {
                    matched = false
                    break
                }
            }
            if (matched) return index
        }
        return -1
    }

    private data class HttpResponse(
        val code: Int,
        val headers: Map<String, String>,
        val body: ByteArray,
    )

    private data class RawHttpResponse(
        val code: Int,
        val headers: Map<String, String>,
        val body: ByteArray,
    ) {
        fun header(name: String): String? =
            headers.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value
    }

    private class RawHttpExchange(
        private val socket: Socket,
    ) {
        private val input = socket.getInputStream()
        private val output = socket.getOutputStream()

        fun writeRequest(request: String) {
            output.write(request.toByteArray(Charsets.ISO_8859_1))
            output.flush()
        }

        fun readResponse(expectBody: Boolean = true): RawHttpResponse {
            val headerBytes = readHeaderBytes()
            val headerBlock = headerBytes.toString(Charsets.ISO_8859_1)
            val (code, headers) = parseHeaderBlock(headerBlock)
            val contentLength = headers.entries
                .firstOrNull { it.key.equals("Content-Length", ignoreCase = true) }
                ?.value
                ?.toIntOrNull()
                ?: 0
            val body = if (expectBody && contentLength > 0) {
                readExactly(contentLength)
            } else {
                ByteArray(0)
            }
            return RawHttpResponse(code, headers, body)
        }

        fun readResponseUntilClosed(): RawHttpResponse {
            val headerBytes = readHeaderBytes()
            val headerBlock = headerBytes.toString(Charsets.ISO_8859_1)
            val (code, headers) = parseHeaderBlock(headerBlock)
            val body = ByteArrayOutputStream()
            while (true) {
                val next = try {
                    input.read()
                } catch (error: SocketTimeoutException) {
                    throw AssertionError("timed out waiting for server to close the socket", error)
                }
                if (next == -1) {
                    return RawHttpResponse(code, headers, body.toByteArray())
                }
                body.write(next)
            }
        }

        fun assertClosed() {
            val previousTimeout = socket.soTimeout
            socket.soTimeout = 500
            try {
                val next = input.read()
                assertEquals("expected server to close the socket", -1, next)
            } catch (error: SocketTimeoutException) {
                throw AssertionError("timed out waiting for server to close the socket", error)
            } finally {
                socket.soTimeout = previousTimeout
            }
        }

        private fun parseHeaderBlock(headerBlock: String): Pair<Int, Map<String, String>> {
            val lines = headerBlock.split("\r\n").filter { it.isNotEmpty() }
            val statusLine = lines.firstOrNull() ?: error("missing status line")
            val code = statusLine.split(' ', limit = 3).getOrNull(1)?.toIntOrNull()
                ?: error("missing status code in $statusLine")
            val headers = lines.drop(1).mapNotNull { line ->
                val separator = line.indexOf(':')
                if (separator <= 0) {
                    null
                } else {
                    line.substring(0, separator) to line.substring(separator + 1).trim()
                }
            }.toMap()
            return code to headers
        }

        private fun readHeaderBytes(): ByteArray {
            val buffer = ByteArrayOutputStream()
            val terminator = "\r\n\r\n".toByteArray(Charsets.ISO_8859_1)
            while (true) {
                val next = input.read()
                if (next == -1) {
                    throw AssertionError("connection closed before response headers")
                }
                buffer.write(next)
                val bytes = buffer.toByteArray()
                val headerEnd = bytes.indexOfPattern(terminator)
                if (headerEnd >= 0) {
                    return bytes.copyOfRange(0, headerEnd)
                }
            }
        }

        private fun readExactly(length: Int): ByteArray {
            val body = ByteArray(length)
            var offset = 0
            while (offset < length) {
                val count = input.read(body, offset, length - offset)
                if (count == -1) {
                    throw AssertionError("connection closed before $length body bytes")
                }
                offset += count
            }
            return body
        }

        private fun ByteArray.indexOfPattern(pattern: ByteArray): Int {
            if (pattern.isEmpty() || size < pattern.size) return -1
            for (index in 0..size - pattern.size) {
                var matched = true
                for (patternIndex in pattern.indices) {
                    if (this[index + patternIndex] != pattern[patternIndex]) {
                        matched = false
                        break
                    }
                }
                if (matched) return index
            }
            return -1
        }
    }

    private class RecordingClient(
        private val bytes: ByteArray,
    ) : WebDavClient {
        val openRangeCalls = CopyOnWriteArrayList<Pair<Long, Long?>>()
        val fullStreamCalls = CopyOnWriteArrayList<String>()

        override suspend fun list(path: String) = emptyList<com.example.comicdav.network.WebDavItem>()

        override suspend fun head(path: String): RemoteFileInfo =
            RemoteFileInfo(path, bytes.size.toLong(), etag = null, lastModified = null, supportsRange = true)

        override suspend fun readRange(path: String, start: Long, endInclusive: Long): ByteArray =
            bytes.sliceArray(start.toInt()..endInclusive.toInt())

        override suspend fun openFullStream(path: String): WebDavStreamResponse {
            fullStreamCalls += path
            return WebDavStreamResponse(
                stream = ByteArrayInputStream(bytes),
                statusCode = 200,
                contentLength = bytes.size.toLong(),
                contentRange = null,
                contentType = "video/mp4",
                totalSize = bytes.size.toLong(),
                close = {},
            )
        }

        override suspend fun openRangeStream(
            path: String,
            start: Long,
            endInclusive: Long?,
        ): WebDavStreamResponse {
            openRangeCalls += start to endInclusive
            val end = endInclusive ?: bytes.lastIndex.toLong()
            val chunk = bytes.sliceArray(start.toInt()..end.toInt())
            return WebDavStreamResponse(
                stream = ByteArrayInputStream(chunk),
                statusCode = 206,
                contentLength = chunk.size.toLong(),
                contentRange = ContentRange(start, end, bytes.size.toLong()),
                contentType = "video/mp4",
                totalSize = bytes.size.toLong(),
                close = {},
            )
        }

        override suspend fun download(path: String, target: File, onBytesRead: (Long) -> Unit): Long =
            error("download is not used by video proxy tests")
    }

    private class MismatchedRangeBodyClient(
        private val rangeBody: ByteArray,
    ) : WebDavClient {
        override suspend fun list(path: String) = emptyList<com.example.comicdav.network.WebDavItem>()

        override suspend fun head(path: String): RemoteFileInfo =
            RemoteFileInfo(path, 10L, etag = null, lastModified = null, supportsRange = true)

        override suspend fun readRange(path: String, start: Long, endInclusive: Long): ByteArray =
            error("readRange is not used by video proxy tests")

        override suspend fun openRangeStream(
            path: String,
            start: Long,
            endInclusive: Long?,
        ): WebDavStreamResponse =
            WebDavStreamResponse(
                stream = ByteArrayInputStream(rangeBody),
                statusCode = 206,
                contentLength = rangeBody.size.toLong(),
                contentRange = null,
                contentType = "video/mp4",
                totalSize = 10L,
                close = {},
            )

        override suspend fun download(path: String, target: File, onBytesRead: (Long) -> Unit): Long =
            error("download is not used by video proxy tests")
    }

    private class SlowAfterFirstByteRangeClient(
        private val bytes: ByteArray,
    ) : WebDavClient {
        private val released = CountDownLatch(1)

        override suspend fun list(path: String) = emptyList<com.example.comicdav.network.WebDavItem>()

        override suspend fun head(path: String): RemoteFileInfo =
            RemoteFileInfo(path, bytes.size.toLong(), etag = null, lastModified = null, supportsRange = true)

        override suspend fun readRange(path: String, start: Long, endInclusive: Long): ByteArray =
            error("readRange is not used by video proxy tests")

        override suspend fun openRangeStream(
            path: String,
            start: Long,
            endInclusive: Long?,
        ): WebDavStreamResponse {
            val end = endInclusive ?: bytes.lastIndex.toLong()
            val chunk = bytes.sliceArray(start.toInt()..end.toInt())
            return WebDavStreamResponse(
                stream = SlowAfterFirstByteInputStream(chunk, released),
                statusCode = 206,
                contentLength = chunk.size.toLong(),
                contentRange = ContentRange(start, end, bytes.size.toLong()),
                contentType = "video/mp4",
                totalSize = bytes.size.toLong(),
                close = { released.countDown() },
            )
        }

        override suspend fun download(path: String, target: File, onBytesRead: (Long) -> Unit): Long =
            error("download is not used by video proxy tests")

        fun release() {
            released.countDown()
        }
    }

    private class SlowAfterFirstByteInputStream(
        private val bytes: ByteArray,
        private val released: CountDownLatch,
    ) : InputStream() {
        private var offset = 0

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            if (this.offset >= bytes.size) return -1
            if (this.offset > 0) {
                released.await(2, TimeUnit.SECONDS)
            }
            buffer[offset] = bytes[this.offset]
            this.offset += 1
            return 1
        }

        override fun read(): Int {
            val one = ByteArray(1)
            val count = read(one, 0, 1)
            return if (count == -1) -1 else one[0].toInt() and 0xff
        }

        override fun close() {
            released.countDown()
        }
    }

    private class FailingClient(
        private val headError: Throwable? = null,
        private val streamError: Throwable? = null,
    ) : WebDavClient {
        val openRangeCallCount = AtomicInteger(0)

        override suspend fun list(path: String) = emptyList<com.example.comicdav.network.WebDavItem>()

        override suspend fun head(path: String): RemoteFileInfo {
            headError?.let { throw it }
            return RemoteFileInfo(path, 10L, etag = null, lastModified = null, supportsRange = true)
        }

        override suspend fun readRange(path: String, start: Long, endInclusive: Long): ByteArray =
            error("readRange is not used by video proxy tests")

        override suspend fun openRangeStream(
            path: String,
            start: Long,
            endInclusive: Long?,
        ): WebDavStreamResponse {
            openRangeCallCount.incrementAndGet()
            streamError?.let { throw it }
            val chunk = "012".toByteArray()
            return WebDavStreamResponse(
                stream = ByteArrayInputStream(chunk),
                statusCode = 206,
                contentLength = chunk.size.toLong(),
                contentRange = ContentRange(start, endInclusive ?: start + chunk.lastIndex, 10L),
                contentType = "video/mp4",
                totalSize = 10L,
                close = {},
            )
        }

        override suspend fun download(path: String, target: File, onBytesRead: (Long) -> Unit): Long =
            error("download is not used by video proxy tests")
    }

    private class OptimizedRangeFailingClient(
        private val bytes: ByteArray,
    ) : WebDavClient {
        val openRangeCalls = mutableListOf<Pair<Long, Long?>>()

        override suspend fun list(path: String) = emptyList<com.example.comicdav.network.WebDavItem>()

        override suspend fun head(path: String): RemoteFileInfo =
            RemoteFileInfo(path, bytes.size.toLong(), etag = null, lastModified = null, supportsRange = true)

        override suspend fun readRange(path: String, start: Long, endInclusive: Long): ByteArray =
            bytes.sliceArray(start.toInt()..endInclusive.toInt())

        override suspend fun openRangeStream(
            path: String,
            start: Long,
            endInclusive: Long?,
        ): WebDavStreamResponse {
            val end = endInclusive ?: bytes.lastIndex.toLong()
            openRangeCalls += start to end
            if (start == 0L && end == bytes.lastIndex.toLong()) {
                throw WebDavException.Network("optimized segment failed")
            }
            val chunk = bytes.sliceArray(start.toInt()..end.toInt())
            return WebDavStreamResponse(
                stream = ByteArrayInputStream(chunk),
                statusCode = 206,
                contentLength = chunk.size.toLong(),
                contentRange = ContentRange(start, end, bytes.size.toLong()),
                contentType = "video/mp4",
                totalSize = bytes.size.toLong(),
                close = {},
            )
        }

        override suspend fun download(path: String, target: File, onBytesRead: (Long) -> Unit): Long =
            error("download is not used by video proxy tests")
    }

    private class BlockingInputStream : InputStream() {
        val readStarted = CountDownLatch(1)
        val closed = CountDownLatch(1)
        private val isClosed = AtomicBoolean(false)

        override fun read(): Int {
            readStarted.countDown()
            while (!isClosed.get()) {
                Thread.sleep(10)
            }
            return -1
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int = read()

        override fun close() {
            forceClose()
        }

        fun forceClose() {
            isClosed.set(true)
            closed.countDown()
        }
    }

    private class BlockingStreamClient(
        private val input: BlockingInputStream,
    ) : WebDavClient {
        override suspend fun list(path: String) = emptyList<com.example.comicdav.network.WebDavItem>()

        override suspend fun head(path: String): RemoteFileInfo =
            RemoteFileInfo(path, 10L, etag = null, lastModified = null, supportsRange = true)

        override suspend fun readRange(path: String, start: Long, endInclusive: Long): ByteArray =
            error("readRange is not used by video proxy tests")

        override suspend fun openRangeStream(
            path: String,
            start: Long,
            endInclusive: Long?,
        ): WebDavStreamResponse =
            WebDavStreamResponse(
                stream = input,
                statusCode = 206,
                contentLength = 10L,
                contentRange = ContentRange(start, endInclusive ?: 9L, 10L),
                contentType = "video/mp4",
                totalSize = 10L,
                close = input::close,
            )

        override suspend fun download(path: String, target: File, onBytesRead: (Long) -> Unit): Long =
            error("download is not used by video proxy tests")
    }

    private class CancellableOpeningRangeClient : WebDavClient {
        val openStarted = CountDownLatch(1)
        val cancelled = CountDownLatch(1)
        val cancellableOverloadUsed = AtomicBoolean(false)
        private val released = CountDownLatch(1)

        override suspend fun list(path: String) = emptyList<com.example.comicdav.network.WebDavItem>()

        override suspend fun head(path: String): RemoteFileInfo =
            RemoteFileInfo(path, 10L, etag = null, lastModified = null, supportsRange = true)

        override suspend fun readRange(path: String, start: Long, endInclusive: Long): ByteArray =
            error("readRange is not used by video proxy tests")

        override suspend fun openRangeStream(
            path: String,
            start: Long,
            endInclusive: Long?,
        ): WebDavStreamResponse {
            openStarted.countDown()
            released.await(2, TimeUnit.SECONDS)
            throw IOException("released")
        }

        override suspend fun openRangeStream(
            path: String,
            start: Long,
            endInclusive: Long?,
            registerCancellation: (Closeable) -> Unit,
        ): WebDavStreamResponse {
            cancellableOverloadUsed.set(true)
            registerCancellation(
                Closeable {
                    cancelled.countDown()
                    released.countDown()
                },
            )
            openStarted.countDown()
            released.await(2, TimeUnit.SECONDS)
            throw IOException("released")
        }

        override suspend fun download(path: String, target: File, onBytesRead: (Long) -> Unit): Long =
            error("download is not used by video proxy tests")

        fun release() {
            released.countDown()
        }
    }

    private class UnknownTotalRangeClient : WebDavClient {
        private val bytes = "0123456789".toByteArray()

        override suspend fun list(path: String) = emptyList<com.example.comicdav.network.WebDavItem>()

        override suspend fun head(path: String): RemoteFileInfo =
            RemoteFileInfo(path, bytes.size.toLong(), etag = null, lastModified = null, supportsRange = true)

        override suspend fun readRange(path: String, start: Long, endInclusive: Long): ByteArray =
            error("readRange is not used by video proxy tests")

        override suspend fun openRangeStream(
            path: String,
            start: Long,
            endInclusive: Long?,
        ): WebDavStreamResponse {
            val end = endInclusive ?: bytes.lastIndex.toLong()
            val chunk = bytes.sliceArray(start.toInt()..end.toInt())
            return WebDavStreamResponse(
                stream = ByteArrayInputStream(chunk),
                statusCode = 206,
                contentLength = chunk.size.toLong(),
                contentRange = ContentRange(start, end, -1L),
                contentType = "video/mp4",
                totalSize = null,
                close = {},
            )
        }

        override suspend fun download(path: String, target: File, onBytesRead: (Long) -> Unit): Long =
            error("download is not used by video proxy tests")
    }

    private class FlakyAcceptServerSocket(
        bindAddress: InetSocketAddress,
    ) : ServerSocket() {
        private val failedOnce = AtomicBoolean(false)

        init {
            bind(bindAddress, 50)
        }

        override fun accept(): Socket {
            if (failedOnce.compareAndSet(false, true)) {
                throw java.io.IOException("transient accept failure")
            }
            return super.accept()
        }
    }
}
