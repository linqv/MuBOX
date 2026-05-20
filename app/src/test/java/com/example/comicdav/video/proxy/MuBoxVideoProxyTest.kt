package com.example.comicdav.video.proxy

import com.example.comicdav.network.ContentRange
import com.example.comicdav.network.RemoteFileInfo
import com.example.comicdav.network.WebDavClient
import com.example.comicdav.network.WebDavException
import com.example.comicdav.network.WebDavStreamResponse
import com.example.comicdav.video.WebDavVideoOpenRequest
import java.io.ByteArrayInputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.ServerSocket
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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
        assertEquals(listOf(0L to null), client.openRangeCalls)
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
        val streamId = streamUrl.substringAfterLast('/')
        proxy?.unregister(streamId)

        val response = httpRequest(streamUrl, method = "GET")

        assertEquals(404, response.code)
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

    private data class HttpResponse(
        val code: Int,
        val headers: Map<String, String>,
        val body: ByteArray,
    )

    private class RecordingClient(
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
}
