package com.example.comicdav.network

import java.io.Closeable
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import okhttp3.Credentials
import okhttp3.MediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import okio.BufferedSource
import okio.Source
import okio.Timeout
import okio.buffer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.After
import org.junit.Before
import org.junit.Test

class OkHttpWebDavClientTest {
    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun openRangeStreamSendsRangeHeaderAndReturnsStreamMetadata() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(206)
                .setHeader("Content-Range", "bytes 2-4/10")
                .setHeader("Content-Type", "video/mp4")
                .setBody("234"),
        )
        val client = OkHttpWebDavClient(
            baseUrl = server.url("/dav/").toString(),
            username = null,
            password = null,
        )

        val response = client.openRangeStream("/movie.mp4", start = 2L, endInclusive = 4L)
        val bytes = try {
            response.stream.readBytes()
        } finally {
            response.close()
        }

        assertArrayEquals("234".toByteArray(), bytes)
        assertEquals(206, response.statusCode)
        assertEquals(3L, response.contentLength)
        assertEquals(ContentRange(2L, 4L, 10L), response.contentRange)
        assertEquals("video/mp4", response.contentType)
        assertEquals(10L, response.totalSize)
        assertEquals("bytes=2-4", server.takeRequest().getHeader("Range"))
    }

    @Test
    fun remoteOkForRangeRequestIsMappedToRangeNotSupported() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("full response"),
        )
        val client = OkHttpWebDavClient(
            baseUrl = server.url("/dav/").toString(),
            username = null,
            password = null,
        )

        val result = runCatching {
            client.openRangeStream("/movie.mp4", start = 0L, endInclusive = 3L)
        }

        assertTrue(result.exceptionOrNull() is WebDavException.RangeNotSupported)
        assertEquals("bytes=0-3", server.takeRequest().getHeader("Range"))
    }

    @Test
    fun readRangeRetriesTransientServerFailure() = runTest {
        server.enqueue(MockResponse().setResponseCode(502))
        server.enqueue(
            MockResponse()
                .setResponseCode(206)
                .setHeader("Content-Range", "bytes 0-2/3")
                .setBody("abc"),
        )
        val client = OkHttpWebDavClient(
            baseUrl = server.url("/dav/").toString(),
            username = null,
            password = null,
        )

        val bytes = client.readRange("/movie.mp4", start = 0L, endInclusive = 2L)

        assertArrayEquals("abc".toByteArray(), bytes)
        assertEquals("bytes=0-2", server.takeRequest().getHeader("Range"))
        assertEquals("bytes=0-2", server.takeRequest().getHeader("Range"))
    }

    @Test
    fun cancelledRangeRequestDoesNotRetry() = runTest {
        val client = OkHttpWebDavClient(
            baseUrl = server.url("/dav/").toString(),
            username = null,
            password = null,
        )
        var cancellationRegistrations = 0

        val result = runCatching {
            client.openRangeStream("/movie.mp4", start = 0L, endInclusive = 2L) { closeable ->
                cancellationRegistrations++
                closeable.close()
            }
        }

        assertTrue(result.exceptionOrNull() is CancellationException)
        assertEquals(1, cancellationRegistrations)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun cancelledRangeResponseDoesNotLogFailureDiagnostics() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(206)
                .setHeader("Content-Range", "bytes 0-1048575/1048576")
                .setBody(Buffer().write(ByteArray(1024 * 1024)))
                .throttleBody(1, 1, TimeUnit.SECONDS),
        )
        val failureLogs = mutableListOf<String>()
        val client = OkHttpWebDavClient(
            baseUrl = server.url("/dav/").toString(),
            username = null,
            password = null,
            httpClient = OkHttpClient.Builder()
                .readTimeout(1, TimeUnit.SECONDS)
                .build(),
            diagnostics = recordingDiagnostics(failureLogs = failureLogs),
        )
        var cancellation: Closeable? = null

        val response = client.openRangeStream("/movie.mp4", start = 0L, endInclusive = 1_048_575L) { closeable ->
            cancellation = closeable
        }
        cancellation?.close()
        try {
            runCatching { response.stream.readBytes() }
        } finally {
            response.close()
        }

        assertTrue(failureLogs.isEmpty())
    }

    @Test
    fun openFullStreamDoesNotSendRangeHeader() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "video/mp4")
                .setHeader("Content-Length", "3")
                .setBody("abc"),
        )
        val client = OkHttpWebDavClient(
            baseUrl = server.url("/dav/").toString(),
            username = null,
            password = null,
        )

        val response = client.openFullStream("/movie.mp4")
        val bytes = try {
            response.stream.readBytes()
        } finally {
            response.close()
        }

        assertEquals(200, response.statusCode)
        assertEquals(3L, response.contentLength)
        assertEquals("video/mp4", response.contentType)
        assertArrayEquals("abc".toByteArray(), bytes)
        assertEquals(null, server.takeRequest().getHeader("Range"))
    }

    @Test
    fun openFullStreamRejectsPartialContentResponse() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(206)
                .setHeader("Content-Range", "bytes 0-2/3")
                .setBody("abc"),
        )
        val client = OkHttpWebDavClient(
            baseUrl = server.url("/dav/").toString(),
            username = null,
            password = null,
        )

        val result = runCatching {
            client.openFullStream("/movie.mp4")
        }

        assertTrue(result.exceptionOrNull() is WebDavException.HttpStatus)
    }

    @Test
    fun contentRangeEndMustBeInsideTotalSize() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(206)
                .setHeader("Content-Range", "bytes 2-4/4")
                .setBody("234"),
        )
        val client = OkHttpWebDavClient(
            baseUrl = server.url("/dav/").toString(),
            username = null,
            password = null,
        )

        val result = runCatching {
            client.openRangeStream("/movie.mp4", start = 2L, endInclusive = 4L)
        }

        assertTrue(result.exceptionOrNull() is WebDavException.InvalidContentRange)
    }

    @Test
    fun defaultOpenRangeStreamDoesNotBufferThroughReadRange() = runTest {
        val client = DefaultStreamClient()

        val result = runCatching {
            client.openRangeStream("/movie.mp4", start = 0L, endInclusive = null)
        }

        assertTrue(result.exceptionOrNull() is UnsupportedOperationException)
        assertFalse(client.readRangeCalled)
    }

    @Test
    fun invalidContentRangeClosesResponseBody() = runTest {
        val body = CloseTrackingBody("abc")
        val httpClient = OkHttpClient.Builder()
            .addInterceptor { chain ->
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(206)
                    .message("Partial Content")
                    .header("Content-Range", "bytes 1-3/10")
                    .body(body)
                    .build()
            }
            .build()
        val client = OkHttpWebDavClient(
            baseUrl = "http://example.test/dav/",
            username = null,
            password = null,
            httpClient = httpClient,
        )

        val result = runCatching {
            client.openRangeStream("/movie.mp4", start = 0L, endInclusive = 3L)
        }

        assertTrue(result.exceptionOrNull() is WebDavException.InvalidContentRange)
        assertTrue(body.closed)
    }

    @Test
    fun rangeRequestsCarryDiagnosticTagWithOperationPathAndRange() = runTest {
        val tags = mutableListOf<WebDavRequestTag>()
        val httpClient = OkHttpClient.Builder()
            .addInterceptor { chain ->
                tags += requireNotNull(chain.request().tag(WebDavRequestTag::class.java))
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(206)
                    .message("Partial Content")
                    .header("Content-Range", "bytes 2-4/10")
                    .body("234".toResponseBody())
                    .build()
            }
            .build()
        val client = OkHttpWebDavClient(
            baseUrl = "http://example.test/dav/",
            username = null,
            password = null,
            httpClient = httpClient,
            diagnostics = recordingDiagnostics(),
        )

        val response = client.openRangeStream("/movie.mp4", start = 2L, endInclusive = 4L)
        response.close()

        assertEquals(1, tags.size)
        assertEquals(WebDavOperation.RANGE_GET, tags.single().operation)
        assertEquals("/movie.mp4", tags.single().path)
        assertEquals("bytes=2-4", tags.single().rangeHeader)
    }

    @Test
    fun detailDiagnosticsLogNetworkPhaseDurations() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Length", "3")
                .setBody("abc"),
        )
        val logs = mutableListOf<String>()
        val client = OkHttpWebDavClient(
            baseUrl = server.url("/dav/").toString(),
            username = null,
            password = null,
            diagnostics = recordingDiagnostics(detailLogs = logs),
        )

        client.head("/movie.mp4")

        val complete = logs.single { it.contains("event=complete") }
        assertTrue(complete.contains("operation=HEAD"))
        assertTrue(complete.contains("pathId=webdav:"))
        assertTrue(complete.contains("dnsMs="))
        assertTrue(complete.contains("connectMs="))
        assertTrue(complete.contains("tlsMs="))
        assertTrue(complete.contains("responseMs="))
        assertTrue(complete.contains("bodyMs="))
        assertTrue(complete.contains("totalMs="))
    }

    @Test
    fun failureDiagnosticsLogSanitizedCurlCommand() = runTest {
        server.enqueue(MockResponse().setResponseCode(401))
        val logs = mutableListOf<String>()
        val client = OkHttpWebDavClient(
            baseUrl = server.url("/dav/").toString(),
            username = "alice",
            password = "secret",
            diagnostics = recordingDiagnostics(failureLogs = logs),
        )
        val credentialUrl = server.url("/private/movie.mp4?token=open-sesame")
            .newBuilder()
            .username("url-user")
            .password("url-password")
            .build()
            .toString()

        val result = runCatching { client.head(credentialUrl) }

        assertTrue(result.exceptionOrNull() is WebDavException.HttpStatus)
        val combined = logs.joinToString("\n")
        assertTrue(combined.contains("event=failure"))
        assertTrue(combined.contains("operation=HEAD"))
        assertTrue(combined.contains("curl="))
        assertTrue(doesNotContainAuthorization(combined))
        assertFalse(combined.contains(Credentials.basic("alice", "secret")))
        assertFalse(combined.contains("url-user:url-password@"))
        assertFalse(combined.contains("token=open-sesame"))
        assertTrue(combined.contains("token=%3Credacted%3E") || combined.contains("token=<redacted>"))
        assertFalse(result.exceptionOrNull()?.message.orEmpty().contains("url-user:url-password@"))
        assertFalse(result.exceptionOrNull()?.message.orEmpty().contains("token=open-sesame"))
    }

    private class CloseTrackingBody(
        text: String,
    ) : ResponseBody() {
        var closed: Boolean = false
            private set

        private val bytes = text.toByteArray()
        private val source = object : Source {
            private val buffer = Buffer().write(bytes)

            override fun read(sink: Buffer, byteCount: Long): Long =
                buffer.read(sink, byteCount)

            override fun timeout(): Timeout = Timeout.NONE

            override fun close() {
                closed = true
                buffer.clear()
            }
        }.buffer()

        override fun contentType(): MediaType = "video/mp4".toMediaType()

        override fun contentLength(): Long = bytes.size.toLong()

        override fun source(): BufferedSource = source
    }

    private class DefaultStreamClient : WebDavClient {
        var readRangeCalled = false
            private set

        override suspend fun list(path: String): List<WebDavItem> = emptyList()

        override suspend fun head(path: String): RemoteFileInfo =
            RemoteFileInfo(path, 1L, etag = null, lastModified = null, supportsRange = true)

        override suspend fun readRange(path: String, start: Long, endInclusive: Long): ByteArray {
            readRangeCalled = true
            return byteArrayOf(1)
        }

        override suspend fun download(path: String, target: java.io.File, onBytesRead: (Long) -> Unit): Long =
            error("download is not used by this test")
    }

    private fun recordingDiagnostics(
        detailLogs: MutableList<String> = mutableListOf(),
        failureLogs: MutableList<String> = mutableListOf(),
    ): WebDavNetworkDiagnostics =
        WebDavNetworkDiagnostics(
            logDetail = { event -> detailLogs += event() },
            logFailure = { message, _ -> failureLogs += message },
        )

    private fun String.toResponseBody(): ResponseBody =
        toResponseBody("text/plain".toMediaType())

    private fun doesNotContainAuthorization(text: String): Boolean =
        !text.contains("Authorization", ignoreCase = true)
}
