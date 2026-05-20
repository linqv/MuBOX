package com.example.comicdav.network

import kotlinx.coroutines.test.runTest
import okhttp3.MediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody
import okhttp3.MediaType.Companion.toMediaType
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
}
