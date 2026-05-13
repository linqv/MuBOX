package com.example.comicdav.data

import com.example.comicdav.network.OkHttpWebDavClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ComicDownloadCacheTest {
    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun sameRemoteIdentityReturnsSameCacheKey() {
        val first = ComicCacheKey.fromRemote(
            accountId = "https://example.test/dav|user",
            remotePath = "/books/demo.cbz",
            size = 1024,
            etag = "\"abc\"",
            lastModified = 99,
        )
        val second = ComicCacheKey.fromRemote(
            accountId = "https://example.test/dav|user",
            remotePath = "/books/demo.cbz",
            size = 1024,
            etag = "\"abc\"",
            lastModified = 123,
        )

        assertEquals(first, second)
    }

    @Test
    fun changedEtagReturnsDifferentCacheKey() {
        val first = ComicCacheKey.fromRemote(
            accountId = "https://example.test/dav|user",
            remotePath = "/books/demo.cbz",
            size = 1024,
            etag = "\"abc\"",
            lastModified = 99,
        )
        val second = ComicCacheKey.fromRemote(
            accountId = "https://example.test/dav|user",
            remotePath = "/books/demo.cbz",
            size = 1024,
            etag = "\"def\"",
            lastModified = 99,
        )

        assertNotEquals(first, second)
    }

    @Test
    fun downloadStreamsBodyToFinalCacheFile() = runTest {
        val bytes = ByteArray(1_200_000) { index -> (index % 251).toByte() }
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody(okio.Buffer().write(bytes)))
            server.start()
            val client = OkHttpWebDavClient(server.url("/dav/").toString(), null, null)
            val cache = ComicDownloadCache(temp.root)

            val result = cache.download(
                client = client,
                remotePath = "/books/book.cbz",
                key = ComicCacheKey("abc"),
                expectedSize = bytes.size.toLong(),
            )

            assertEquals(bytes.size.toLong(), result.length())
            assertEquals("abc.cbz", result.name)
            assertEquals(bytes.last(), result.readBytes().last())
            assertFalse(temp.root.resolve("abc.tmp").exists())
        }
    }

    @Test
    fun downloadReusesExistingFinalFileWhenSizeMatches() = runTest {
        val existing = temp.root.resolve("cached.cbz")
        existing.writeBytes(byteArrayOf(1, 2, 3))
        val client = FailingDownloadClient()
        val cache = ComicDownloadCache(temp.root)

        val result = cache.download(
            client = client,
            remotePath = "/books/book.cbz",
            key = ComicCacheKey("cached"),
            expectedSize = 3,
        )

        assertEquals(existing, result)
    }

    @Test
    fun cancellationDeletesTmpFile() = runTest {
        val client = SuspendingDownloadClient()
        val cache = ComicDownloadCache(temp.root)

        val job = launch {
            cache.download(
                client = client,
                remotePath = "/books/book.cbz",
                key = ComicCacheKey("cancelled"),
                expectedSize = 3,
            )
        }
        client.awaitStarted()
        job.cancelAndJoin()

        assertFalse(temp.root.resolve("cancelled.tmp").exists())
    }

    private class FailingDownloadClient : com.example.comicdav.network.WebDavClient {
        override suspend fun list(path: String) = error("unused")
        override suspend fun head(path: String) = error("unused")
        override suspend fun readRange(path: String, start: Long, endInclusive: Long): ByteArray = error("unused")
        override suspend fun download(path: String, target: java.io.File, onBytesRead: (Long) -> Unit): Long {
            error("cache should have been reused")
        }
    }

    private class SuspendingDownloadClient : com.example.comicdav.network.WebDavClient {
        private val started = kotlinx.coroutines.CompletableDeferred<Unit>()

        suspend fun awaitStarted() {
            started.await()
        }

        override suspend fun list(path: String) = error("unused")
        override suspend fun head(path: String) = error("unused")
        override suspend fun readRange(path: String, start: Long, endInclusive: Long): ByteArray = error("unused")

        override suspend fun download(path: String, target: java.io.File, onBytesRead: (Long) -> Unit): Long {
            target.writeBytes(byteArrayOf(1, 2))
            started.complete(Unit)
            throw CancellationException("cancelled")
        }
    }
}
