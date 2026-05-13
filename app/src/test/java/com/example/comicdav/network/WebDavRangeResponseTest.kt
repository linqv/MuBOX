package com.example.comicdav.network

import kotlinx.coroutines.test.runTest
import okhttp3.Credentials
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class WebDavRangeResponseTest {
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
    fun treatsOkResponseAsRangeUnsupported() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Length", "10")
                .setBody("0123456789"),
        )
        val client = OkHttpWebDavClient(server.url("/dav/").toString(), username = null, password = null)

        val error = runCatching { client.readRange("/books/book.cbz", 2, 4) }.exceptionOrNull()

        assertTrue(error is WebDavException.RangeNotSupported)
    }
}
