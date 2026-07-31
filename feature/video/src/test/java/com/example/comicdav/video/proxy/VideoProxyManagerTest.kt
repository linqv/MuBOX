package com.example.comicdav.video.proxy

import com.example.comicdav.core.model.settings.VideoProxySettings
import com.example.comicdav.core.model.media.WebDavSubtitleOpenRequest
import com.example.comicdav.core.model.media.WebDavVideoOpenRequest
import com.example.comicdav.core.remote.ContentRange
import com.example.comicdav.core.remote.RemoteFileInfo
import com.example.comicdav.core.remote.WebDavClient
import com.example.comicdav.core.remote.WebDavClientFactory
import com.example.comicdav.core.remote.WebDavItem
import com.example.comicdav.core.remote.WebDavStreamResponse
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.Base64
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class VideoProxyManagerTest {
    private lateinit var server: MockWebServer
    private lateinit var manager: VideoProxyManager

    @Before
    fun setUp() {
        manager = VideoProxyManager()
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        manager.close()
        server.shutdown()
    }

    @Test
    fun openUsesProvidedAccountSnapshotWithoutWaitingForPersistedStore() = runTest {
        server.enqueueRange("abc", total = 3)

        val session = manager.open(
            request = request(size = 3),
            clientFactory = clientFactory(),
        )
        val response = httpRequest(session.url, range = "bytes=0-2")

        assertEquals(206, response.code)
        assertArrayEquals("abc".toByteArray(), response.body)
        val remoteRequest = server.takeRequest()
        assertEquals("GET", remoteRequest.method)
        assertEquals("bytes=0-2", remoteRequest.getHeader("Range"))
        assertEquals(basicCredentials("user", "pass"), remoteRequest.getHeader("Authorization"))
    }

    @Test
    fun getWithoutLocalRangeUsesFullRemoteGetWithoutRangeHeader() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "video/mp4")
                .setHeader("Content-Length", "3")
                .setBody("abc"),
        )

        val session = manager.open(
            request = request(size = 3),
            clientFactory = clientFactory(),
        )
        val response = httpRequest(session.url, range = null)

        assertEquals(200, response.code)
        assertArrayEquals("abc".toByteArray(), response.body)
        val remoteRequest = server.takeRequest()
        assertEquals("GET", remoteRequest.method)
        assertEquals(null, remoteRequest.getHeader("Range"))
        manager.close(session.streamId)
    }

    @Test
    fun openPassesDisabledSeekOptimizationToRegisteredStream() = runTest {
        server.enqueueRange("012", total = 10)
        server.enqueueRange("123", total = 10, start = 1)

        val session = manager.open(
            request = request(size = 10),
            clientFactory = clientFactory(),
            proxySettings = VideoProxySettings.DEFAULT.copy(seekOptimizationEnabled = false),
        )

        assertArrayEquals("012".toByteArray(), httpRequest(session.url, range = "bytes=0-2").body)
        assertArrayEquals("123".toByteArray(), httpRequest(session.url, range = "bytes=1-3").body)
        assertEquals("bytes=0-2", server.takeRequest().getHeader("Range"))
        assertEquals("bytes=1-3", server.takeRequest().getHeader("Range"))

        manager.close(session.streamId)
    }

    @Test
    fun closeThenOpenAgainStartsUsableProxy() = runTest {
        server.enqueueRange("a", total = 1)
        server.enqueueRange("b", total = 1)

        val first = manager.open(request = request(size = 1), clientFactory = clientFactory())
        assertArrayEquals("a".toByteArray(), httpRequest(first.url, range = "bytes=0-0").body)
        manager.close(first.streamId)

        val second = manager.open(request = request(size = 1), clientFactory = clientFactory())
        assertArrayEquals("b".toByteArray(), httpRequest(second.url, range = "bytes=0-0").body)
        manager.close(second.streamId)
    }

    @Test
    fun closingManagerIsTerminalForItsScopedLifecycle() = runTest {
        manager.close()

        val error = runCatching {
            manager.open(request = request(size = 1), clientFactory = clientFactory())
        }.exceptionOrNull()

        assertTrue(error is IllegalStateException)
    }

    @Test
    fun closingUnknownStreamDoesNotShutdownActiveProxy() = runTest {
        server.enqueueRange("a", total = 1)

        val session = manager.open(request = request(size = 1), clientFactory = clientFactory())
        manager.close("missing-stream")
        try {
            assertArrayEquals("a".toByteArray(), httpRequest(session.url, range = "bytes=0-0").body)
        } finally {
            manager.close(session.streamId)
        }
    }

    @Test
    fun sessionsKeepTheirAccountSnapshotWhenSameAccountIdIsOpenedAgain() = runTest {
        val firstServer = MockWebServer()
        val secondServer = MockWebServer()
        firstServer.start()
        secondServer.start()
        try {
            firstServer.enqueueRange("a", total = 1)
            secondServer.enqueueRange("b", total = 1)

            val first = manager.open(
                request = request(size = 1, accountId = "account-1"),
                clientFactory = clientFactory(
                    baseUrl = firstServer.url("/dav/").toString(),
                    password = "first-pass",
                ),
            )
            val second = manager.open(
                request = request(size = 1, accountId = "account-1"),
                clientFactory = clientFactory(
                    baseUrl = secondServer.url("/dav/").toString(),
                    password = "second-pass",
                ),
            )

            assertEquals(URL(first.url).port, URL(second.url).port)
            assertArrayEquals("a".toByteArray(), httpRequest(first.url, range = "bytes=0-0").body)
            manager.close(first.streamId)
            assertArrayEquals("b".toByteArray(), httpRequest(second.url, range = "bytes=0-0").body)
            assertEquals(basicCredentials("user", "first-pass"), firstServer.takeRequest().getHeader("Authorization"))
            assertEquals(basicCredentials("user", "second-pass"), secondServer.takeRequest().getHeader("Authorization"))

            manager.close(second.streamId)
        } finally {
            firstServer.shutdown()
            secondServer.shutdown()
        }
    }

    @Test
    fun openRegistersSidecarSubtitleStreamsWithSameAccountSnapshot() = runTest {
        server.enqueueRange("vid", total = 3)
        server.enqueueRange("sub!", total = 4)

        val session = manager.open(
            request = request(
                size = 3,
                subtitles = listOf(
                    WebDavSubtitleOpenRequest(
                        remotePath = "/movie.zh.srt",
                        displayName = "movie.zh.srt",
                        size = 4L,
                        etag = null,
                        lastModified = null,
                        mimeType = "application/x-subrip",
                    ),
                ),
            ),
            clientFactory = clientFactory(),
        )

        assertEquals(1, session.subtitleUrls.size)
        assertTrue(session.subtitleUrls.single().endsWith("/movie.zh.srt"))
        assertEquals(listOf(session.streamId, MuBoxVideoProxy.streamIdFromUrl(session.subtitleUrls.single())), session.streamIds)
        assertArrayEquals("vid".toByteArray(), httpRequest(session.url, range = "bytes=0-2").body)
        assertArrayEquals("sub!".toByteArray(), httpRequest(session.subtitleUrls.single(), range = "bytes=0-3").body)

        val videoRequest = server.takeRequest()
        val subtitleRequest = server.takeRequest()
        assertEquals("/dav/movie.mp4", videoRequest.path?.substringBefore('?'))
        assertEquals("/dav/movie.zh.srt", subtitleRequest.path?.substringBefore('?'))
        assertEquals(basicCredentials("user", "pass"), subtitleRequest.getHeader("Authorization"))

        session.streamIds.forEach(manager::close)
    }

    @Test
    fun closeSessionClosesMainAndSubtitleStreams() = runTest {
        server.enqueueRange("vid", total = 3)
        server.enqueueRange("sub!", total = 4)
        val session = manager.open(
            request = request(
                size = 3,
                subtitles = listOf(
                    WebDavSubtitleOpenRequest(
                        remotePath = "/movie.zh.srt",
                        displayName = "movie.zh.srt",
                        size = 4L,
                        etag = null,
                        lastModified = null,
                        mimeType = "application/x-subrip",
                    ),
                ),
            ),
            clientFactory = clientFactory(),
        )

        manager.close(session)

        assertTrue(runCatching { httpRequest(session.url, range = "bytes=0-2") }.isFailure)
        assertTrue(runCatching { httpRequest(session.subtitleUrls.single(), range = "bytes=0-3") }.isFailure)
    }

    private fun MockWebServer.enqueueRange(body: String, total: Long, start: Long = 0) {
        val end = start + body.length - 1
        enqueue(
            MockResponse()
                .setResponseCode(206)
                .setHeader("Content-Range", "bytes $start-$end/$total")
                .setBody(body),
        )
    }

    private fun request(
        size: Long,
        accountId: String = "account-1",
        subtitles: List<WebDavSubtitleOpenRequest> = emptyList(),
    ): WebDavVideoOpenRequest =
        WebDavVideoOpenRequest(
            accountId = accountId,
            remotePath = "/movie.mp4",
            displayName = "movie.mp4",
            size = size,
            etag = null,
            lastModified = null,
            mimeType = "video/mp4",
            subtitles = subtitles,
        )

    private fun clientFactory(
        baseUrl: String = server.url("/dav/").toString(),
        password: String = "pass",
    ): WebDavClientFactory =
        WebDavClientFactory {
            TestHttpWebDavClient(
                baseUrl = baseUrl,
                username = "user",
                password = password,
            )
        }

    private class TestHttpWebDavClient(
        private val baseUrl: String,
        private val username: String,
        private val password: String,
    ) : WebDavClient {
        override suspend fun list(path: String): List<WebDavItem> = error("unused")

        override suspend fun head(path: String): RemoteFileInfo = error("unused")

        override suspend fun readRange(path: String, start: Long, endInclusive: Long): ByteArray =
            openRangeStream(path, start, endInclusive).let { response ->
                try {
                    response.stream.readBytes()
                } finally {
                    response.close()
                }
            }

        override suspend fun openRangeStream(
            path: String,
            start: Long,
            endInclusive: Long?,
        ): WebDavStreamResponse =
            open(path = path, range = "bytes=$start-${endInclusive ?: ""}")

        override suspend fun openFullStream(path: String): WebDavStreamResponse =
            open(path = path, range = null)

        override suspend fun download(
            path: String,
            target: File,
            onBytesRead: (Long) -> Unit,
        ): Long = error("unused")

        private fun open(path: String, range: String?): WebDavStreamResponse {
            val requestUrl = baseUrl.trimEnd('/') + "/" + path.trimStart('/')
            val connection = (URL(requestUrl).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                setRequestProperty("Authorization", basicCredentials(username, password))
                range?.let { setRequestProperty("Range", it) }
            }
            val status = connection.responseCode
            val stream = if (status >= 400) connection.errorStream else connection.inputStream
            val contentRange = parseContentRange(connection.getHeaderField("Content-Range"))
            return WebDavStreamResponse(
                stream = stream,
                statusCode = status,
                contentLength = connection.contentLengthLong,
                contentRange = contentRange,
                contentType = connection.contentType,
                totalSize = contentRange?.totalSize ?: connection.contentLengthLong.takeIf { it >= 0L },
                close = {
                    stream.close()
                    connection.disconnect()
                },
            )
        }
    }

    private companion object {
        private val CONTENT_RANGE = Regex("bytes (\\d+)-(\\d+)/(\\d+)")

        fun parseContentRange(value: String?): ContentRange? {
            val match = value?.let(CONTENT_RANGE::matchEntire) ?: return null
            return ContentRange(
                start = match.groupValues[1].toLong(),
                endInclusive = match.groupValues[2].toLong(),
                totalSize = match.groupValues[3].toLong(),
            )
        }

        fun basicCredentials(username: String, password: String): String {
            val token = Base64.getEncoder().encodeToString("$username:$password".toByteArray())
            return "Basic $token"
        }
    }

    private fun httpRequest(url: String, range: String?): HttpResponse {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 2_000
            readTimeout = 2_000
            range?.let { setRequestProperty("Range", it) }
        }
        val code = connection.responseCode
        val body = (if (code >= 400) connection.errorStream else connection.inputStream)?.readBytes()
            ?: ByteArray(0)
        connection.disconnect()
        return HttpResponse(code, body)
    }

    private data class HttpResponse(
        val code: Int,
        val body: ByteArray,
    )
}
