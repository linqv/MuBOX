package com.example.comicdav.video.proxy

import com.example.comicdav.data.SavedWebDavAccount
import com.example.comicdav.video.WebDavVideoOpenRequest
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.test.runTest
import okhttp3.Credentials
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

class VideoProxyManagerTest {
    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        VideoProxyManager.shutdown()
        server.shutdown()
    }

    @Test
    fun openUsesProvidedAccountSnapshotWithoutWaitingForPersistedStore() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(206)
                .setHeader("Content-Range", "bytes 0-2/3")
                .setBody("abc"),
        )

        val session = VideoProxyManager.open(
            request = request(size = 3),
            account = account(),
        )
        val response = httpRequest(session.url, range = "bytes=0-2")

        assertEquals(206, response.code)
        assertArrayEquals("abc".toByteArray(), response.body)
        val remoteRequest = server.takeRequest()
        assertEquals("GET", remoteRequest.method)
        assertEquals("bytes=0-2", remoteRequest.getHeader("Range"))
        assertEquals(Credentials.basic("user", "pass"), remoteRequest.getHeader("Authorization"))
    }

    @Test
    fun closeThenOpenAgainStartsUsableProxy() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(206)
                .setHeader("Content-Range", "bytes 0-0/1")
                .setBody("a"),
        )
        server.enqueue(
            MockResponse()
                .setResponseCode(206)
                .setHeader("Content-Range", "bytes 0-0/1")
                .setBody("b"),
        )

        val first = VideoProxyManager.open(request = request(size = 1), account = account())
        assertArrayEquals("a".toByteArray(), httpRequest(first.url, range = "bytes=0-0").body)
        VideoProxyManager.close(first.streamId)

        val second = VideoProxyManager.open(request = request(size = 1), account = account())
        assertArrayEquals("b".toByteArray(), httpRequest(second.url, range = "bytes=0-0").body)
        VideoProxyManager.close(second.streamId)
    }

    @Test
    fun closingUnknownStreamDoesNotShutdownActiveProxy() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(206)
                .setHeader("Content-Range", "bytes 0-0/1")
                .setBody("a"),
        )

        val session = VideoProxyManager.open(request = request(size = 1), account = account())
        VideoProxyManager.close("missing-stream")
        try {
            assertArrayEquals("a".toByteArray(), httpRequest(session.url, range = "bytes=0-0").body)
        } finally {
            VideoProxyManager.close(session.streamId)
        }
    }

    private fun request(size: Long): WebDavVideoOpenRequest =
        WebDavVideoOpenRequest(
            accountId = account().accountId,
            remotePath = "/movie.mp4",
            displayName = "movie.mp4",
            size = size,
            etag = null,
            lastModified = null,
            mimeType = "video/mp4",
        )

    private fun account(): SavedWebDavAccount =
        SavedWebDavAccount(
            accountId = "account-1",
            baseUrl = server.url("/dav/").toString(),
            username = "user",
            password = "pass",
        )

    private fun httpRequest(url: String, range: String): HttpResponse {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 2_000
            readTimeout = 2_000
            setRequestProperty("Range", range)
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
