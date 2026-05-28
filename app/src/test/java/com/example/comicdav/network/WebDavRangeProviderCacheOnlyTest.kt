package com.example.comicdav.network

import com.example.comicdav.nativebridge.RangeProviderRegistry
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WebDavRangeProviderCacheOnlyTest {
    @Test
    fun prefetchedRangeCanBeCheckedAndReadFromCacheWithoutWebDavRequest() {
        val bytes = ByteArray(128) { it.toByte() }
        val client = RecordingWebDavClient(bytes)
        val provider = WebDavRangeProvider(
            client = client,
            path = "/books/book.cbz",
            size = bytes.size.toLong(),
            readAheadBytes = 0,
            logDiagnostic = {},
        )

        assertTrue(provider.prefetchRange(start = 40, endInclusive = 79))

        assertTrue(provider.isRangeCached(start = 40, endInclusive = 79))
        assertTrue(provider.isRangeCached(start = 50, endInclusive = 59))
        assertFalse(provider.isRangeCached(start = 39, endInclusive = 40))
        assertArrayEquals(bytes.sliceArray(50..59), provider.readCachedRange(start = 50, endInclusive = 59))

        assertNull(provider.readCachedRange(start = 39, endInclusive = 40))
        assertNull(provider.readCachedRange(start = 80, endInclusive = 89))
        assertEquals(listOf(40L to 79L), client.rangeCalls)
    }

    @Test
    fun readCachedRangeDoesNotJoinCoveringInFlightPrefetch() {
        val bytes = ByteArray(128) { it.toByte() }
        val release = CompletableDeferred<Unit>()
        val firstReadStarted = CountDownLatch(1)
        val client = BlockingWebDavClient(
            bytes = bytes,
            release = release,
            firstReadStarted = firstReadStarted,
        )
        val provider = WebDavRangeProvider(
            client = client,
            path = "/books/book.cbz",
            size = bytes.size.toLong(),
            readAheadBytes = 0,
            logDiagnostic = {},
        )

        val prefetchThread = Thread {
            provider.prefetchRange(start = 40, endInclusive = 79)
        }
        prefetchThread.start()
        assertTrue(firstReadStarted.await(1, TimeUnit.SECONDS))
        val startedAt = System.nanoTime()
        val cached = provider.readCachedRange(start = 50, endInclusive = 59)
        val elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt)

        assertNull(cached)
        assertTrue("cache-only read should not block on in-flight fetch", elapsedMillis < 200L)
        assertEquals(listOf(40L to 79L), client.rangeCalls)

        release.complete(Unit)
        prefetchThread.join(1_000)
        assertFalse(prefetchThread.isAlive)
        assertArrayEquals(bytes.sliceArray(50..59), provider.readCachedRange(start = 50, endInclusive = 59))
        assertEquals(listOf(40L to 79L), client.rangeCalls)
    }

    @Test
    fun readRangeDoesNotJoinCoveringInFlightPrefetch() {
        val bytes = ByteArray(128) { it.toByte() }
        val release = CompletableDeferred<Unit>()
        val firstReadStarted = CountDownLatch(1)
        val client = BlockingFirstRangeWebDavClient(
            bytes = bytes,
            releaseFirstRead = release,
            firstReadStarted = firstReadStarted,
        )
        val provider = WebDavRangeProvider(
            client = client,
            path = "/books/book.cbz",
            size = bytes.size.toLong(),
            readAheadBytes = 0,
            logDiagnostic = {},
        )

        val prefetchThread = Thread {
            provider.prefetchRange(start = 40, endInclusive = 79)
        }
        prefetchThread.start()
        assertTrue(firstReadStarted.await(1, TimeUnit.SECONDS))
        val startedAt = System.nanoTime()
        val selectedPageBytes = provider.readRange(fileId = 1L, start = 50, endInclusive = 59)
        val elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt)

        assertArrayEquals(bytes.sliceArray(50..59), selectedPageBytes)
        assertTrue("selected-page read should not wait for prefetch", elapsedMillis < 200L)
        assertEquals(listOf(40L to 79L, 50L to 59L), client.rangeCalls)

        release.complete(Unit)
        prefetchThread.join(1_000)
        assertFalse(prefetchThread.isAlive)
    }

    @Test
    fun registryExposesCacheOnlyRangeRead() {
        val bytes = ByteArray(128) { it.toByte() }
        val provider = WebDavRangeProvider(
            client = RecordingWebDavClient(bytes),
            path = "/books/book.cbz",
            size = bytes.size.toLong(),
            readAheadBytes = 0,
            logDiagnostic = {},
        )
        val fileId = RangeProviderRegistry.register(provider)

        try {
            assertFalse(RangeProviderRegistry.isRangeCached(fileId, start = 40, endInclusive = 49))
            assertNull(RangeProviderRegistry.readCachedRange(fileId, start = 40, endInclusive = 49))

            assertTrue(RangeProviderRegistry.prefetchRange(fileId, start = 40, endInclusive = 49))

            assertTrue(RangeProviderRegistry.isRangeCached(fileId, start = 40, endInclusive = 49))
            assertArrayEquals(
                bytes.sliceArray(40..49),
                RangeProviderRegistry.readCachedRange(fileId, start = 40, endInclusive = 49),
            )
        } finally {
            RangeProviderRegistry.unregister(fileId)
        }
    }

    private class RecordingWebDavClient(
        private val bytes: ByteArray,
    ) : WebDavClient {
        val rangeCalls = mutableListOf<Pair<Long, Long>>()

        override suspend fun list(path: String): List<WebDavItem> = emptyList()

        override suspend fun head(path: String): RemoteFileInfo =
            RemoteFileInfo(path, bytes.size.toLong(), etag = null, lastModified = null, supportsRange = true)

        override suspend fun readRange(path: String, start: Long, endInclusive: Long): ByteArray {
            rangeCalls += start to endInclusive
            return bytes.sliceArray(start.toInt()..endInclusive.toInt())
        }

        override suspend fun download(path: String, target: File, onBytesRead: (Long) -> Unit): Long =
            error("unused")
    }

    private class BlockingWebDavClient(
        private val bytes: ByteArray,
        private val release: CompletableDeferred<Unit>,
        private val firstReadStarted: CountDownLatch,
    ) : WebDavClient {
        val rangeCalls = mutableListOf<Pair<Long, Long>>()

        override suspend fun list(path: String): List<WebDavItem> = emptyList()

        override suspend fun head(path: String): RemoteFileInfo =
            RemoteFileInfo(path, bytes.size.toLong(), etag = null, lastModified = null, supportsRange = true)

        override suspend fun readRange(path: String, start: Long, endInclusive: Long): ByteArray {
            rangeCalls += start to endInclusive
            firstReadStarted.countDown()
            release.await()
            return bytes.sliceArray(start.toInt()..endInclusive.toInt())
        }

        override suspend fun download(path: String, target: File, onBytesRead: (Long) -> Unit): Long =
            error("unused")
    }

    private class BlockingFirstRangeWebDavClient(
        private val bytes: ByteArray,
        private val releaseFirstRead: CompletableDeferred<Unit>,
        private val firstReadStarted: CountDownLatch,
    ) : WebDavClient {
        val rangeCalls = mutableListOf<Pair<Long, Long>>()
        private var shouldBlockNextRead = true

        override suspend fun list(path: String): List<WebDavItem> = emptyList()

        override suspend fun head(path: String): RemoteFileInfo =
            RemoteFileInfo(path, bytes.size.toLong(), etag = null, lastModified = null, supportsRange = true)

        override suspend fun readRange(path: String, start: Long, endInclusive: Long): ByteArray {
            rangeCalls += start to endInclusive
            if (shouldBlockNextRead) {
                shouldBlockNextRead = false
                firstReadStarted.countDown()
                releaseFirstRead.await()
            }
            return bytes.sliceArray(start.toInt()..endInclusive.toInt())
        }

        override suspend fun download(path: String, target: File, onBytesRead: (Long) -> Unit): Long =
            error("unused")
    }
}
