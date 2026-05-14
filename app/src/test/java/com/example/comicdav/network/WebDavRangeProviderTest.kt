package com.example.comicdav.network

import com.example.comicdav.feature.reader.ReaderDiagnosticLog
import com.example.comicdav.feature.reader.ReaderLogSink
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WebDavRangeProviderTest {
    @Test
    fun readAheadServesCoveredNextRangeFromMergedWindow() {
        val bytes = ByteArray(128) { it.toByte() }
        val client = RecordingWebDavClient(bytes)
        val provider = WebDavRangeProvider(
            client = client,
            path = "/books/book.cbz",
            size = bytes.size.toLong(),
            readAheadBytes = 32,
        )

        assertArrayEquals(bytes.sliceArray(10..19), provider.readRange(fileId = 1, start = 10, endInclusive = 19))
        assertArrayEquals(bytes.sliceArray(30..40), provider.readRange(fileId = 1, start = 30, endInclusive = 40))

        assertEquals(listOf(10L to 51L), client.rangeCalls)
    }

    @Test
    fun nonOverlappingWindowsAreRetainedUntilCapacityIsExceeded() {
        val bytes = ByteArray(128) { it.toByte() }
        val client = RecordingWebDavClient(bytes)
        val provider = WebDavRangeProvider(
            client = client,
            path = "/books/book.cbz",
            size = bytes.size.toLong(),
            readAheadBytes = 0,
            maxCacheBytes = 30,
        )

        provider.readRange(fileId = 1, start = 0, endInclusive = 9)
        provider.readRange(fileId = 1, start = 20, endInclusive = 29)
        provider.readRange(fileId = 1, start = 40, endInclusive = 49)

        assertArrayEquals(bytes.sliceArray(0..9), provider.readRange(fileId = 1, start = 0, endInclusive = 9))
        assertArrayEquals(bytes.sliceArray(20..29), provider.readRange(fileId = 1, start = 20, endInclusive = 29))
        assertArrayEquals(bytes.sliceArray(40..49), provider.readRange(fileId = 1, start = 40, endInclusive = 49))

        assertEquals(listOf(0L to 9L, 20L to 29L, 40L to 49L), client.rangeCalls)
    }

    @Test
    fun lruEvictionRemovesLeastRecentlyAccessedWindowWhenCapacityIsExceeded() {
        val bytes = ByteArray(128) { it.toByte() }
        val client = RecordingWebDavClient(bytes)
        val provider = WebDavRangeProvider(
            client = client,
            path = "/books/book.cbz",
            size = bytes.size.toLong(),
            readAheadBytes = 0,
            maxCacheBytes = 20,
        )

        provider.readRange(fileId = 1, start = 0, endInclusive = 9)
        provider.readRange(fileId = 1, start = 20, endInclusive = 29)
        provider.readRange(fileId = 1, start = 0, endInclusive = 9)
        provider.readRange(fileId = 1, start = 40, endInclusive = 49)

        assertArrayEquals(bytes.sliceArray(0..9), provider.readRange(fileId = 1, start = 0, endInclusive = 9))
        assertEquals(listOf(0L to 9L, 20L to 29L, 40L to 49L), client.rangeCalls)

        assertArrayEquals(bytes.sliceArray(20..29), provider.readRange(fileId = 1, start = 20, endInclusive = 29))
        assertEquals(listOf(0L to 9L, 20L to 29L, 40L to 49L, 20L to 29L), client.rangeCalls)
    }

    @Test
    fun oversizedResponseIsNotCachedPastHardCapacityLimit() {
        val bytes = ByteArray(128) { it.toByte() }
        val client = RecordingWebDavClient(bytes)
        val provider = WebDavRangeProvider(
            client = client,
            path = "/books/book.cbz",
            size = bytes.size.toLong(),
            readAheadBytes = 0,
            maxCacheBytes = 8,
        )

        assertArrayEquals(bytes.sliceArray(10..19), provider.readRange(fileId = 1, start = 10, endInclusive = 19))
        assertArrayEquals(bytes.sliceArray(10..19), provider.readRange(fileId = 1, start = 10, endInclusive = 19))

        assertEquals(listOf(10L to 19L, 10L to 19L), client.rangeCalls)
    }

    @Test
    fun requestLargerThanReadAheadStillReturnsOnlyRequestedBytes() {
        val bytes = ByteArray(128) { it.toByte() }
        val client = RecordingWebDavClient(bytes)
        val provider = WebDavRangeProvider(
            client = client,
            path = "/books/book.cbz",
            size = bytes.size.toLong(),
            readAheadBytes = 5,
        )

        assertArrayEquals(bytes.sliceArray(10..30), provider.readRange(fileId = 1, start = 10, endInclusive = 30))
        assertEquals(listOf(10L to 35L), client.rangeCalls)
    }

    @Test
    fun cacheMissClampsExpandedEndToFileEnd() {
        val bytes = ByteArray(64) { it.toByte() }
        val client = RecordingWebDavClient(bytes)
        val provider = WebDavRangeProvider(
            client = client,
            path = "/books/book.cbz",
            size = bytes.size.toLong(),
            readAheadBytes = 32,
        )

        assertArrayEquals(bytes.sliceArray(50..55), provider.readRange(fileId = 1, start = 50, endInclusive = 55))
        assertEquals(listOf(50L to 63L), client.rangeCalls)
    }

    @Test
    fun prefetchedPlannedRangeServesLaterReadWithoutAnotherWebDavRequest() {
        val bytes = ByteArray(128) { it.toByte() }
        val client = RecordingWebDavClient(bytes)
        val provider = WebDavRangeProvider(
            client = client,
            path = "/books/book.cbz",
            size = bytes.size.toLong(),
            readAheadBytes = 0,
        )

        assertTrue(provider.prefetchRange(start = 40, endInclusive = 79))
        assertArrayEquals(bytes.sliceArray(50..59), provider.readRange(fileId = 1, start = 50, endInclusive = 59))

        assertEquals(listOf(40L to 79L), client.rangeCalls)
    }

    @Test
    fun readRangeJoinsCoveringInFlightPrefetchWithoutSecondWebDavRequest() {
        val sink = CollectingReaderLogSink()
        ReaderDiagnosticLog.setSink(sink)
        try {
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
            )

            val prefetchThread = Thread {
                provider.prefetchRange(start = 40, endInclusive = 79)
            }
            prefetchThread.start()
            assertTrue(firstReadStarted.await(1, TimeUnit.SECONDS))

            val readThreadResult = mutableListOf<ByteArray>()
            val readThread = Thread {
                readThreadResult += provider.readRange(fileId = 1, start = 50, endInclusive = 59)
            }
            readThread.start()
            Thread.sleep(100)

            assertEquals(listOf(40L to 79L), client.rangeCalls)
            release.complete(Unit)
            prefetchThread.join(1_000)
            readThread.join(1_000)

            assertArrayEquals(bytes.sliceArray(50..59), readThreadResult.single())
            assertEquals(listOf(40L to 79L), client.rangeCalls)
            assertTrue(sink.lines.any { it.contains("range_inflight_join") && it.contains("start=50") && it.contains("end=59") })
        } finally {
            ReaderDiagnosticLog.clearSink()
        }
    }

    @Test
    fun concurrentReadRangesJoinTheFirstCoveringFetch() {
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
            readAheadBytes = 32,
        )

        val firstResult = mutableListOf<ByteArray>()
        val secondResult = mutableListOf<ByteArray>()
        val firstThread = Thread {
            firstResult += provider.readRange(fileId = 1, start = 10, endInclusive = 19)
        }
        val secondThread = Thread {
            secondResult += provider.readRange(fileId = 1, start = 30, endInclusive = 40)
        }

        firstThread.start()
        assertTrue(firstReadStarted.await(1, TimeUnit.SECONDS))
        secondThread.start()
        Thread.sleep(100)

        assertEquals(listOf(10L to 51L), client.rangeCalls)
        release.complete(Unit)
        firstThread.join(1_000)
        secondThread.join(1_000)

        assertArrayEquals(bytes.sliceArray(10..19), firstResult.single())
        assertArrayEquals(bytes.sliceArray(30..40), secondResult.single())
        assertEquals(listOf(10L to 51L), client.rangeCalls)
    }

    @Test
    fun rangeCacheDiagnosticsIncludeHitMissStoreAndEvict() {
        val sink = CollectingReaderLogSink()
        ReaderDiagnosticLog.setSink(sink)
        try {
            val bytes = ByteArray(128) { it.toByte() }
            val client = RecordingWebDavClient(bytes)
            val provider = WebDavRangeProvider(
                client = client,
                path = "/books/book.cbz",
                size = bytes.size.toLong(),
                readAheadBytes = 0,
                maxCacheBytes = 20,
            )

            provider.readRange(fileId = 1, start = 0, endInclusive = 9)
            provider.readRange(fileId = 1, start = 0, endInclusive = 9)
            provider.readRange(fileId = 1, start = 20, endInclusive = 29)
            provider.readRange(fileId = 1, start = 40, endInclusive = 49)

            assertTrue(sink.lines.any { it.contains("range_cache_miss") })
            assertTrue(sink.lines.any { it.contains("range_cache_store") })
            assertTrue(sink.lines.any { it.contains("range_cache_hit") })
            assertTrue(sink.lines.any { it.contains("range_cache_evict") })
        } finally {
            ReaderDiagnosticLog.clearSink()
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

        override suspend fun download(path: String, target: File, onBytesRead: (Long) -> Unit): Long {
            error("unused")
        }
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

        override suspend fun download(path: String, target: File, onBytesRead: (Long) -> Unit): Long {
            error("unused")
        }
    }

    private class CollectingReaderLogSink : ReaderLogSink {
        val lines = mutableListOf<String>()

        override fun log(line: String) {
            lines += line
        }

        override fun logBlocking(line: String) {
            lines += line
        }
    }
}
