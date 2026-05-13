package com.example.comicdav.network

import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

class OkHttpWebDavClientPathTest {
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
        val client = OkHttpWebDavClient(server.url("/webdav/").toString(), username = null, password = null)

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
        val client = OkHttpWebDavClient(server.url("/webdav/").toString(), username = null, password = null)

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
        val client = OkHttpWebDavClient(server.url("/webdav/").toString(), username = null, password = null)

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
        val client = OkHttpWebDavClient(server.url("/webdav/").toString(), username = null, password = null)

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
        )

        client.list("/webdav/%E6%BC%AB%E7%94%BB/books/")

        assertEquals("/webdav/%E6%BC%AB%E7%94%BB/books/", server.takeRequest().path)
    }
}
