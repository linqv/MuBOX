package com.example.comicdav.network

import java.io.Closeable
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import okhttp3.Credentials
import okhttp3.Call
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
        val client = testClient()

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
        val client = testClient()

        val result = runCatching {
            client.openRangeStream("/movie.mp4", start = 0L, endInclusive = 3L)
        }

        assertTrue(result.exceptionOrNull() is WebDavException.RangeNotSupported)
        assertEquals("bytes=0-3", server.takeRequest().getHeader("Range"))
    }

    @Test
    fun readRangeRetriesTransientServerFailure() = runTest {
        server.enqueue(MockResponse().setResponseCode(502))
        enqueueRangeResponse(206, "bytes 0-2/3", "abc")
        val client = testClient(
            baseUrl = server.url("/dav/").toString(),
        )

        val bytes = client.readRange("/movie.mp4", start = 0L, endInclusive = 2L)

        assertArrayEquals("abc".toByteArray(), bytes)
        assertEquals("bytes=0-2", server.takeRequest().getHeader("Range"))
        assertEquals("bytes=0-2", server.takeRequest().getHeader("Range"))
    }

    @Test
    fun cancelledRangeRequestDoesNotRetry() = runTest {
        val client = testClient(
            baseUrl = server.url("/dav/").toString(),
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
        val client = testClient(
            baseUrl = server.url("/dav/").toString(),
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
        val client = testClient(
            baseUrl = server.url("/dav/").toString(),
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
        enqueueRangeResponse(206, "bytes 0-2/3", "abc")
        val client = testClient(
            baseUrl = server.url("/dav/").toString(),
        )

        val result = runCatching {
            client.openFullStream("/movie.mp4")
        }

        assertTrue(result.exceptionOrNull() is WebDavException.HttpStatus)
    }

    @Test
    fun headParsesLastModifiedHeaderAsEpochMillis() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Length", "3")
                .setHeader("Last-Modified", "Wed, 21 Oct 2015 07:28:00 GMT"),
        )
        val client = testClient(
            baseUrl = server.url("/dav/").toString(),
        )

        val info = client.head("/movie.mp4")

        assertEquals(1_445_412_480_000L, info.lastModified)
    }

    @Test
    fun headFallsBackToDepthZeroPropfindWhenContentLengthIsMissing() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Last-Modified", "Wed, 21 Oct 2015 07:28:00 GMT")
                .removeHeader("Content-Length"),
        )
        server.enqueue(
            MockResponse()
                .setResponseCode(207)
                .setHeader("Content-Type", "application/xml")
                .setBody(
                    """<?xml version="1.0"?>
                    <d:multistatus xmlns:d="DAV:">
                        <d:response>
                            <d:href>/dav/movie.mp4</d:href>
                            <d:propstat>
                                <d:prop>
                                    <d:getcontentlength>123</d:getcontentlength>
                                    <d:getetag>"etag-1"</d:getetag>
                                    <d:getlastmodified>Wed, 21 Oct 2015 07:28:00 GMT</d:getlastmodified>
                                </d:prop>
                                <d:status>HTTP/1.1 200 OK</d:status>
                            </d:propstat>
                        </d:response>
                    </d:multistatus>
                    """.trimIndent(),
                ),
        )
        val client = testClient(
            baseUrl = server.url("/dav/").toString(),
        )

        val info = client.head("/movie.mp4")

        assertEquals("/movie.mp4", info.path)
        assertEquals(123L, info.size)
        assertEquals("\"etag-1\"", info.etag)
        assertEquals(1_445_412_480_000L, info.lastModified)
        assertEquals("HEAD", server.takeRequest().method)
        val propfind = server.takeRequest()
        assertEquals("PROPFIND", propfind.method)
        assertEquals("0", propfind.getHeader("Depth"))
    }

    @Test
    fun listWrapsMalformedPropfindXmlAsInvalidResponse() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(207)
                .setHeader("Content-Type", "application/xml")
                .setBody("""<?xml version="0.0"?><d:multistatus xmlns:d="DAV:" />"""),
        )
        val client = testClient(
            baseUrl = server.url("/dav/").toString(),
        )

        val result = runCatching {
            client.list("/")
        }

        val error = result.exceptionOrNull()
        assertTrue(error is WebDavException.InvalidResponse)
        assertEquals("Invalid WebDAV PROPFIND response", error?.message)
        assertTrue(error?.cause != null)
    }

    @Test
    fun listRejectsNonMultistatusPropfindXmlAsInvalidResponse() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(207)
                .setHeader("Content-Type", "application/xml")
                .setBody("""<html><body>login required</body></html>"""),
        )
        val client = testClient(
            baseUrl = server.url("/dav/").toString(),
        )

        val result = runCatching {
            client.list("/")
        }

        val error = result.exceptionOrNull()
        assertTrue(error is WebDavException.InvalidResponse)
        assertEquals("Invalid WebDAV PROPFIND response", error?.message)
    }

    @Test
    fun basicAuthUsesUtf8Credentials() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Length", "3"),
        )
        val client = testClient(
            baseUrl = server.url("/dav/").toString(),
            username = "用户",
            password = "秘密",
        )

        client.head("/movie.mp4")

        assertEquals(
            Credentials.basic("用户", "秘密", Charsets.UTF_8),
            server.takeRequest().getHeader("Authorization"),
        )
    }

    @Test
    fun headWrapsIOExceptionAsNetworkException() = runTest {
        val httpClient = OkHttpClient.Builder()
            .addInterceptor {
                throw IOException("network down")
            }
            .build()
        val client = testClient(
            baseUrl = "http://example.test/dav/",
            httpClient = httpClient,
        )

        val result = runCatching { client.head("/movie.mp4") }
        val error = result.exceptionOrNull()

        assertTrue(error is WebDavException.Network)
        assertTrue(error?.message.orEmpty().contains("network down"))
    }

    @Test
    fun httpsBaseUrlRejectsPlaintextAbsoluteRequestUrl() = runTest {
        val httpClient = OkHttpClient.Builder()
            .addInterceptor {
                error("Request should be rejected before the HTTP client executes it")
            }
            .build()
        val client = OkHttpWebDavClient(
            baseUrl = "https://example.test/dav/",
            username = null,
            password = null,
            httpClient = httpClient,
        )

        val result = runCatching { client.head("http://example.test/dav/movie.mp4") }

        assertTrue(result.exceptionOrNull() is WebDavException.Network)
    }

    @Test
    fun rejectsCrossOriginAbsoluteUrlEvenWhenPathEmbedsPlaintextUrl() = runTest {
        val httpClient = OkHttpClient.Builder()
            .addInterceptor {
                error("Request should be rejected before the HTTP client executes it")
            }
            .build()
        val client = OkHttpWebDavClient(
            baseUrl = "https://example.test/dav/",
            username = null,
            password = null,
            httpClient = httpClient,
        )

        val result = runCatching {
            client.head("https://attacker.test/redirect/http://internal.local")
        }

        assertTrue(result.exceptionOrNull() is WebDavException.Network)
    }

    @Test
    fun rangeRequestFollowsCrossOriginRedirectWithoutForwardingAuthorization() = runTest {
        MockWebServer().use { redirectedServer ->
            redirectedServer.start()
            server.enqueue(
                MockResponse()
                    .setResponseCode(302)
                    .setHeader("Location", redirectedServer.url("/download/movie.mp4").toString()),
            )
            redirectedServer.enqueue(
                MockResponse()
                    .setResponseCode(206)
                    .setHeader("Content-Range", "bytes 0-2/3")
                    .setBody("abc"),
            )
            val client = testClient(
                baseUrl = server.url("/dav/").toString(),
                username = "alice",
                password = "secret",
            )

            val bytes = client.readRange("/movie.mp4", start = 0L, endInclusive = 2L)

            assertArrayEquals("abc".toByteArray(), bytes)
            assertEquals(Credentials.basic("alice", "secret", Charsets.UTF_8), server.takeRequest().getHeader("Authorization"))
            assertEquals(null, redirectedServer.takeRequest().getHeader("Authorization"))
        }
    }

    @Test
    fun headAndPropfindUseBoundedCallTimeout() = runTest {
        val timeoutNanos = mutableListOf<Long>()
        val httpClient = OkHttpClient.Builder()
            .callTimeout(0, TimeUnit.MILLISECONDS)
            .addInterceptor { chain ->
                timeoutNanos += chain.call().timeout().timeoutNanos()
                when (chain.request().method) {
                    "PROPFIND" -> Response.Builder()
                        .request(chain.request())
                        .protocol(Protocol.HTTP_1_1)
                        .code(207)
                        .message("Multi-Status")
                        .body("""<d:multistatus xmlns:d="DAV:" />""".toResponseBody())
                        .build()
                    "HEAD" -> Response.Builder()
                        .request(chain.request())
                        .protocol(Protocol.HTTP_1_1)
                        .code(200)
                        .message("OK")
                        .header("Content-Length", "3")
                        .body("".toResponseBody())
                        .build()
                    else -> error("Unexpected method ${chain.request().method}")
                }
            }
            .build()
        val client = testClient(
            baseUrl = "http://example.test/dav/",
            httpClient = httpClient,
        )

        client.list("/")
        client.head("/movie.mp4")

        assertEquals(
            listOf(
                TimeUnit.SECONDS.toNanos(30),
                TimeUnit.SECONDS.toNanos(30),
            ),
            timeoutNanos,
        )
    }

    @Test
    fun downloadThrottlesProgressCallbacksAndReportsFinalByteCount() = runTest {
        val bytes = ByteArray(1024 * 1024) { (it % 251).toByte() }
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody(Buffer().write(bytes)),
        )
        val client = testClient(
            baseUrl = server.url("/dav/").toString(),
        )
        val target = File.createTempFile("webdav-download", ".bin")
        target.deleteOnExit()
        val progress = mutableListOf<Long>()

        val total = client.download("/movie.mp4", target) { downloaded ->
            progress += downloaded
        }

        assertEquals(bytes.size.toLong(), total)
        assertArrayEquals(bytes, target.readBytes())
        assertEquals(bytes.size.toLong(), progress.last())
        assertTrue("progress=$progress", progress.size <= 6)
    }

    @Test
    fun downloadDoesNotSetOverallCallTimeout() = runTest {
        val timeoutNanos = mutableListOf<Long>()
        val httpClient = OkHttpClient.Builder()
            .callTimeout(0, TimeUnit.MILLISECONDS)
            .addInterceptor { chain ->
                timeoutNanos += chain.call().timeout().timeoutNanos()
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body("abc".toResponseBody())
                    .build()
            }
            .build()
        val client = testClient(
            baseUrl = "http://example.test/dav/",
            httpClient = httpClient,
        )
        val target = File.createTempFile("webdav-download-timeout", ".bin")
        target.deleteOnExit()

        val total = client.download("/movie.mp4", target) {}

        assertEquals(3L, total)
        assertEquals(listOf(0L), timeoutNanos)
    }

    @Test
    fun cancelledDownloadCancelsHttpCall() = runTest {
        val startedCall = CompletableDeferred<Call>()
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody(Buffer().write(ByteArray(1024 * 1024)))
                .throttleBody(1, 1, TimeUnit.SECONDS),
        )
        val httpClient = OkHttpClient.Builder()
            .addInterceptor { chain ->
                startedCall.complete(chain.call())
                chain.proceed(chain.request())
            }
            .readTimeout(5, TimeUnit.SECONDS)
            .build()
        val client = testClient(
            baseUrl = server.url("/dav/").toString(),
            httpClient = httpClient,
        )
        val target = File.createTempFile("webdav-download-cancel", ".bin")
        target.deleteOnExit()
        val job = launch {
            runCatching { client.download("/movie.mp4", target) {} }
        }

        val call = startedCall.await()
        job.cancel()
        delay(50)

        assertTrue(call.isCanceled())
        job.join()
    }

    @Test
    fun diagnosticRequestTagCachesPathId() {
        val tag = WebDavRequestTag(
            operation = WebDavOperation.HEAD,
            path = "/movie.mp4",
            rangeHeader = null,
        )
        var buildCount = 0

        val first = tag.pathIdFor(server.url("/dav/movie.mp4?token=first")) {
            buildCount++
            "webdav:first"
        }
        val second = tag.pathIdFor(server.url("/dav/movie.mp4?token=second")) {
            buildCount++
            "webdav:second"
        }

        assertEquals("webdav:first", first)
        assertEquals(first, second)
        assertEquals(1, buildCount)
    }

    @Test
    fun contentRangeEndMustBeInsideTotalSize() = runTest {
        enqueueRangeResponse(206, "bytes 2-4/4", "234")
        val client = testClient(
            baseUrl = server.url("/dav/").toString(),
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
        val client = testClient(
            baseUrl = "http://example.test/dav/",
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
        val client = testClient(
            baseUrl = "http://example.test/dav/",
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
        val client = testClient(
            baseUrl = server.url("/dav/").toString(),
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
        val client = testClient(
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

    private fun enqueueRangeResponse(code: Int, contentRange: String, body: String) {
        server.enqueue(
            MockResponse()
                .setResponseCode(code)
                .setHeader("Content-Range", contentRange)
                .setBody(body),
        )
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

    private fun testClient(
        baseUrl: String = server.url("/dav/").toString(),
        username: String? = null,
        password: String? = null,
        httpClient: OkHttpClient = HttpClients.webDav,
        diagnostics: WebDavNetworkDiagnostics = recordingDiagnostics(),
    ): OkHttpWebDavClient = OkHttpWebDavClient(
        baseUrl = baseUrl,
        username = username,
        password = password,
        httpClient = httpClient,
        diagnostics = diagnostics,
        allowPlaintextHttp = true,
    )

    @Test
    fun readsValidPartialContentRange() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(206)
                .setHeader("Content-Range", "bytes 2-4/10")
                .setBody("cde"),
        )
        val client = OkHttpWebDavClient(
            baseUrl = server.url("/dav/").toString(),
            username = "user",
            password = "pass",
        )

        val bytes = client.readRange("/books/book.cbz", 2, 4)

        assertArrayEquals(byteArrayOf(99, 100, 101), bytes)
        val request = server.takeRequest()
        assertEquals("GET", request.method)
        assertEquals("bytes=2-4", request.getHeader("Range"))
        assertEquals(Credentials.basic("user", "pass"), request.getHeader("Authorization"))
    }

    @Test
    fun rejectsMismatchedContentRange() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(206)
                .setHeader("Content-Range", "bytes 3-5/10")
                .setBody("cde"),
        )
        val client = OkHttpWebDavClient(server.url("/dav/").toString(), username = null, password = null)

        val error = runCatching { client.readRange("/books/book.cbz", 2, 4) }.exceptionOrNull()

        assertTrue(error is WebDavException.InvalidContentRange)
    }

    @Test
    fun preservesMountedBasePathWhenListingRootRelativeDirectory() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(207)
                .setHeader("Content-Type", "application/xml")
                .setBody(
                    """<?xml version="1.0"?>
                    <d:multistatus xmlns:d="DAV:">
                        <d:response><d:href>/webdav/books/</d:href><d:propstat><d:prop><d:resourcetype><d:collection/></d:resourcetype></d:prop></d:propstat></d:response>
                    </d:multistatus>
                    """.trimIndent(),
                ),
        )
        val client = OkHttpWebDavClient(server.url("/webdav/").toString(), username = null, password = null, allowPlaintextHttp = true)

        client.list("/books/")

        assertEquals("/webdav/books/", server.takeRequest().path)
    }

    @Test
    fun doesNotDuplicateMountedBasePathWhenHrefAlreadyContainsIt() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(207)
                .setHeader("Content-Type", "application/xml")
                .setBody(
                    """<?xml version="1.0"?>
                    <d:multistatus xmlns:d="DAV:">
                        <d:response><d:href>/webdav/books/</d:href><d:propstat><d:prop><d:resourcetype><d:collection/></d:resourcetype></d:prop></d:propstat></d:response>
                    </d:multistatus>
                    """.trimIndent(),
                ),
        )
        val client = OkHttpWebDavClient(server.url("/webdav/").toString(), username = null, password = null, allowPlaintextHttp = true)

        client.list("/webdav/books/")

        assertEquals("/webdav/books/", server.takeRequest().path)
    }

    @Test
    fun encodesChinesePathSegmentsWhenListingDirectory() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(207)
                .setHeader("Content-Type", "application/xml")
                .setBody("""<?xml version="1.0"?><d:multistatus xmlns:d="DAV:" />"""),
        )
        val client = OkHttpWebDavClient(server.url("/webdav/").toString(), username = null, password = null, allowPlaintextHttp = true)

        client.list("/webdav/漫画/")

        assertEquals("/webdav/%E6%BC%AB%E7%94%BB/", server.takeRequest().path)
    }

    @Test
    fun preservesAlreadyEncodedChinesePathSegmentsWhenListingDirectory() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(207)
                .setHeader("Content-Type", "application/xml")
                .setBody("""<?xml version="1.0"?><d:multistatus xmlns:d="DAV:" />"""),
        )
        val client = OkHttpWebDavClient(server.url("/webdav/").toString(), username = null, password = null, allowPlaintextHttp = true)

        client.list("/webdav/%E6%BC%AB%E7%94%BB/")

        assertEquals("/webdav/%E6%BC%AB%E7%94%BB/", server.takeRequest().path)
    }

    @Test
    fun doesNotDuplicateEncodedMountedBasePathWhenHrefAlreadyContainsIt() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(207)
                .setHeader("Content-Type", "application/xml")
                .setBody("""<?xml version="1.0"?><d:multistatus xmlns:d="DAV:" />"""),
        )
        val client = OkHttpWebDavClient(
            server.url("/webdav/%E6%BC%AB%E7%94%BB/").toString(),
            username = null,
            password = null,
            allowPlaintextHttp = true,
        )

        client.list("/webdav/%E6%BC%AB%E7%94%BB/books/")

        assertEquals("/webdav/%E6%BC%AB%E7%94%BB/books/", server.takeRequest().path)
    }

    @Test
    fun doesNotDuplicateEncodedMountedBasePathWhenPathOmitsLeadingSlash() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(207)
                .setHeader("Content-Type", "application/xml")
                .setBody("""<?xml version="1.0"?><d:multistatus xmlns:d="DAV:" />"""),
        )
        val client = OkHttpWebDavClient(
            server.url("/webdav/%E6%BC%AB%E7%94%BB/").toString(),
            username = null,
            password = null,
            allowPlaintextHttp = true,
        )

        client.list("webdav/%E6%BC%AB%E7%94%BB/%E6%A8%A1%E5%9B%A0/")

        assertEquals("/webdav/%E6%BC%AB%E7%94%BB/%E6%A8%A1%E5%9B%A0/", server.takeRequest().path)
    }

    @Test
    fun doesNotDuplicateMountedBasePathWhenBaseUrlHasUnencodedChineseAndPathIsEncoded() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(207)
                .setHeader("Content-Type", "application/xml")
                .setBody("""<?xml version="1.0"?><d:multistatus xmlns:d="DAV:" />"""),
        )
        val client = OkHttpWebDavClient(
            server.url("/webdav/").toString() + "漫画/",
            username = null,
            password = null,
            allowPlaintextHttp = true,
        )

        client.list("webdav/%E6%BC%AB%E7%94%BB/%E6%A8%A1%E5%9B%A0/")

        assertEquals("/webdav/%E6%BC%AB%E7%94%BB/%E6%A8%A1%E5%9B%A0/", server.takeRequest().path)
    }

    @Test
    fun filtersCurrentDirectoryWhenBaseUrlAlreadyIncludesDirectoryPath() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(207)
                .setHeader("Content-Type", "application/xml")
                .setBody(
                    """<?xml version="1.0"?>
                    <d:multistatus xmlns:d="DAV:">
                        <d:response><d:href>/webdav/%E6%BC%AB%E7%94%BB/</d:href><d:propstat><d:prop><d:resourcetype><d:collection/></d:resourcetype></d:prop></d:propstat></d:response>
                        <d:response><d:href>/webdav/%E6%BC%AB%E7%94%BB/%E7%AC%AC01%E5%8D%B7/</d:href><d:propstat><d:prop><d:resourcetype><d:collection/></d:resourcetype></d:prop></d:propstat></d:response>
                        <d:response><d:href>/webdav/%E6%BC%AB%E7%94%BB/Book.cbz</d:href><d:propstat><d:prop><d:getcontentlength>456</d:getcontentlength></d:prop></d:propstat></d:response>
                    </d:multistatus>
                    """.trimIndent(),
                ),
        )
        val client = OkHttpWebDavClient(
            server.url("/webdav/").toString() + "漫画/",
            username = null,
            password = null,
            allowPlaintextHttp = true,
        )

        val items = client.list("/")

        assertEquals("/webdav/%E6%BC%AB%E7%94%BB/", server.takeRequest().path)
        assertEquals(listOf("第01卷", "Book.cbz"), items.map { it.name })
        assertEquals(
            listOf("/webdav/%E6%BC%AB%E7%94%BB/%E7%AC%AC01%E5%8D%B7/", "/webdav/%E6%BC%AB%E7%94%BB/Book.cbz"),
            items.map { it.path },
        )
    }
}
