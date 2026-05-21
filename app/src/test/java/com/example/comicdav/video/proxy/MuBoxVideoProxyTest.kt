package com.example.comicdav.video.proxy

import com.example.comicdav.network.ContentRange
import com.example.comicdav.network.RemoteFileInfo
import com.example.comicdav.network.WebDavClient
import com.example.comicdav.network.WebDavException
import com.example.comicdav.network.WebDavStreamResponse
import com.example.comicdav.video.WebDavVideoOpenRequest
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
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
        assertEquals(listOf(2L to 4L), client.openRangeCalls)
        assertArrayEquals("234".toByteArray(), response.body)
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
        assertEquals(listOf(2L to 4L), client.openRangeCalls)
        assertArrayEquals("234".toByteArray(), body)
    }

    @Test
    fun openEndedRangeIsBoundedBeforeRemoteRequest() = runTest {
        val client = RecordingClient("0123456789".toByteArray())
        val url = startProxy(client = client, size = 10L)

        val response = httpRequest(url, method = "GET", range = "bytes=2-")

        assertEquals(206, response.code)
        assertEquals("bytes 2-9/10", response.headers["Content-Range"])
        assertEquals(listOf(2L to 9L), client.openRangeCalls)
        assertArrayEquals("23456789".toByteArray(), response.body)
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

    private suspend fun startProxy(
        bytes: ByteArray,
        size: Long,
        mimeType: String = "video/mp4",
    ): String = startProxy(
        client = RecordingClient(bytes),
        size = size,
        mimeType = mimeType,
    )

    private suspend fun startProxy(
        client: WebDavClient,
        size: Long?,
        mimeType: String = "video/mp4",
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
                remotePath = "/video.mp4",
                displayName = "video.mp4",
                size = size,
                etag = null,
                lastModified = null,
                mimeType = mimeType,
            ),
        )
    }

    private fun MuBoxVideoProxy.serverSocketForTest(): ServerSocket {
        val field = MuBoxVideoProxy::class.java.getDeclaredField("serverSocket")
        field.isAccessible = true
        return field.get(this) as ServerSocket
    }

    private fun streamIdFromUrl(url: String): String =
        URL(url).path.removePrefix("/stream/").substringBefore('/')

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

    private data class HttpResponse(
        val code: Int,
        val headers: Map<String, String>,
        val body: ByteArray,
    )

    private class RecordingClient(
        private val bytes: ByteArray,
    ) : WebDavClient {
        val openRangeCalls = mutableListOf<Pair<Long, Long?>>()
        val fullStreamCalls = mutableListOf<String>()

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

    private class FailingClient(
        private val headError: Throwable? = null,
        private val streamError: Throwable? = null,
    ) : WebDavClient {
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
}
