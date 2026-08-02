package org.mubox.reader.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
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
        val client = StaticDownloadClient(bytes)
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
    fun downloadPrunesOldestFilesWhenCapacityIsExceeded() = runTest {
        val oldFile = temp.root.resolve("old.cbz")
        oldFile.writeBytes(ByteArray(8) { 1 })
        oldFile.setLastModified(1_000L)
        val newBytes = ByteArray(8) { 2 }
        val client = StaticDownloadClient(newBytes)
        val cache = ComicDownloadCache(temp.root, maxCacheBytes = 10)

        val result = cache.download(
            client = client,
            remotePath = "/books/new.cbz",
            key = ComicCacheKey("new"),
            expectedSize = newBytes.size.toLong(),
        )

        assertTrue(result.exists())
        assertEquals(newBytes.size.toLong(), result.length())
        assertFalse(oldFile.exists())
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

    @Test
    fun clearDeletesDownloadIndexAndNativePagesForKeyOnly() {
        val cache = ComicDownloadCache(temp.root)
        val key = ComicCacheKey("book")
        val download = temp.root.resolve("book.cbz").apply { writeBytes(ByteArray(3)) }
        val temporary = temp.root.resolve("book.tmp").apply { writeBytes(ByteArray(5)) }
        val index = cache.indexCacheFile(key).apply {
            parentFile?.mkdirs()
            writeBytes(ByteArray(7))
        }
        val nativePagesDir = temp.root.resolve("book/pages").apply { mkdirs() }
        val nativePage = nativePagesDir.resolve("page-0.jpg").apply { writeBytes(ByteArray(13)) }
        val similarlyPrefixed = temp.root.resolve("book-extra.cbz").apply {
            writeBytes(ByteArray(11))
        }

        val bytesDeleted = cache.clear(key)

        assertEquals(28L, bytesDeleted)
        assertFalse(download.exists())
        assertFalse(temporary.exists())
        assertFalse(index.exists())
        assertFalse(nativePage.exists())
        assertFalse(nativePagesDir.exists())
        assertTrue(similarlyPrefixed.exists())
    }

    private class FailingDownloadClient : org.mubox.reader.core.remote.WebDavClient {
        override suspend fun list(path: String) = error("unused")
        override suspend fun head(path: String) = error("unused")
        override suspend fun readRange(path: String, start: Long, endInclusive: Long): ByteArray = error("unused")
        override suspend fun download(path: String, target: java.io.File, onBytesRead: (Long) -> Unit): Long {
            error("cache should have been reused")
        }
    }

    private class SuspendingDownloadClient : org.mubox.reader.core.remote.WebDavClient {
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

    private class StaticDownloadClient(private val bytes: ByteArray) : org.mubox.reader.core.remote.WebDavClient {
        override suspend fun list(path: String) = error("unused")
        override suspend fun head(path: String) = error("unused")
        override suspend fun readRange(path: String, start: Long, endInclusive: Long): ByteArray = error("unused")
        override suspend fun download(path: String, target: java.io.File, onBytesRead: (Long) -> Unit): Long {
            target.writeBytes(bytes)
            onBytesRead(bytes.size.toLong())
            return bytes.size.toLong()
        }
    }
}
