package org.mubox.reader.network

import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
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
    fun lowPriorityPrefetchDoesNotEvictWindowIntersectingProtectedRange() {
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

        assertTrue(
            provider.prefetchRange(start = 40, endInclusive = 49, priority = 3, protectedRanges = listOf(5L..6L)),
        )
        assertArrayEquals(bytes.sliceArray(0..9), provider.readRange(fileId = 1, start = 0, endInclusive = 9))

        assertEquals(listOf(0L to 9L, 20L to 29L, 40L to 49L), client.rangeCalls)
    }

    @Test
    fun highPriorityPrefetchEvictsUnprotectedWindowBeforeProtectedWindow() {
            val bytes = ByteArray(128) { it.toByte() }
            val client = RecordingWebDavClient(bytes)
            val provider = WebDavRangeProvider(
                client = client,
                path = "/books/book.cbz",
                size = bytes.size.toLong(),
                readAheadBytes = 0,
                maxCacheBytes = 20,
            )

            assertTrue(provider.prefetchRange(start = 20, endInclusive = 29))
            assertTrue(provider.prefetchRange(start = 0, endInclusive = 9))

            assertTrue(
                provider.prefetchRange(start = 40, endInclusive = 49, priority = 1, protectedRanges = listOf(0L..9L)),
            )
            assertArrayEquals(bytes.sliceArray(0..9), provider.readRange(fileId = 1, start = 0, endInclusive = 9))
            assertArrayEquals(bytes.sliceArray(20..29), provider.readRange(fileId = 1, start = 20, endInclusive = 29))

            assertEquals(listOf(20L to 29L, 0L to 9L, 40L to 49L, 20L to 29L), client.rangeCalls)
    }

    @Test
    fun readRangeReadAheadTrimsInsteadOfEvictingProtectedWindow() {
        val bytes = ByteArray(128) { it.toByte() }
        val client = RecordingWebDavClient(bytes)
        val provider = WebDavRangeProvider(
            client = client,
            path = "/books/book.cbz",
            size = bytes.size.toLong(),
            readAheadBytes = 20,
            maxCacheBytes = 30,
        )

        assertTrue(provider.prefetchRange(start = 0, endInclusive = 9))
        assertTrue(
            provider.prefetchRange(start = 20, endInclusive = 29, priority = 3, protectedRanges = listOf(0L..9L)),
        )

        assertArrayEquals(bytes.sliceArray(40..49), provider.readRange(fileId = 1, start = 40, endInclusive = 49))
        assertArrayEquals(bytes.sliceArray(0..9), provider.readRange(fileId = 1, start = 0, endInclusive = 9))

        assertEquals(listOf(0L to 9L, 20L to 29L, 40L to 69L), client.rangeCalls)
    }

    @Test
    fun readRangeCanEvictOlderWindowIntersectingProtectedRangeWhenCapacityRequiresIt() {
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
        assertTrue(
            provider.prefetchRange(start = 40, endInclusive = 49, priority = 3, protectedRanges = listOf(5L..6L)),
        )

        provider.readRange(fileId = 1, start = 60, endInclusive = 69)
        assertArrayEquals(bytes.sliceArray(0..9), provider.readRange(fileId = 1, start = 0, endInclusive = 9))

        assertEquals(listOf(0L to 9L, 20L to 29L, 40L to 49L, 60L to 69L, 0L to 9L), client.rangeCalls)
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
    fun adjacentPrefetchedSegmentsAreComposedForLaterCrossRangeRead() {
        val bytes = ByteArray(128) { it.toByte() }
        val client = RecordingWebDavClient(bytes)
        val provider = WebDavRangeProvider(
            client = client,
            path = "/books/book.cbz",
            size = bytes.size.toLong(),
            readAheadBytes = 0,
        )

        assertTrue(provider.prefetchRange(start = 40, endInclusive = 79))
        assertTrue(provider.prefetchRange(start = 80, endInclusive = 99))
        assertArrayEquals(bytes.sliceArray(50..90), provider.readRange(fileId = 1, start = 50, endInclusive = 90))

        assertEquals(listOf(40L to 79L, 80L to 99L), client.rangeCalls)
    }

    @Test
    fun truncatedNetworkResponseIsRejectedAndNeverCached() {
        val bytes = ByteArray(64) { it.toByte() }
        val client = TruncatedStreamingWebDavClient(bytes)
        val provider = WebDavRangeProvider(
            client = client,
            path = "/books/book.cbz",
            size = bytes.size.toLong(),
            readAheadBytes = 0,
        )

        val firstError = assertThrows(IOException::class.java) {
            provider.readRange(fileId = 1, start = 10, endInclusive = 19)
        }
        assertTrue(firstError.message.orEmpty().contains("expected=10 actual=9"))
        assertFalse(provider.isRangeCached(start = 10, endInclusive = 19))

        assertThrows(IOException::class.java) {
            provider.readRange(fileId = 1, start = 10, endInclusive = 19)
        }
        assertEquals(listOf(10L to 19L, 10L to 19L), client.rangeCalls)
    }

    @Test
    fun readRangeJoinsCoveringInFlightPrefetch() {
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
            )

            val prefetchThread = Thread {
                provider.prefetchRange(start = 40, endInclusive = 79)
            }
            prefetchThread.start()
            assertTrue(firstReadStarted.await(1, TimeUnit.SECONDS))

            val readResult = mutableListOf<ByteArray>()
            val readThread = Thread {
                readResult += provider.readRange(fileId = 1, start = 50, endInclusive = 59)
            }
            readThread.start()
            Thread.sleep(100)

            assertTrue("readRange should wait for the covering prefetch", readThread.isAlive)
            release.complete(Unit)
            readThread.join(1_000)
            prefetchThread.join(1_000)

            assertFalse(readThread.isAlive)
            assertFalse(prefetchThread.isAlive)
            assertArrayEquals(bytes.sliceArray(50..59), readResult.single())
            assertEquals(listOf(40L to 79L), client.rangeCalls)
    }

    @Test
    fun readRangeFallsBackWhenJoinedPrefetchFails() {
            val bytes = ByteArray(128) { it.toByte() }
            val release = CompletableDeferred<Unit>()
            val firstReadStarted = CountDownLatch(1)
            val client = FailingFirstRangeWebDavClient(
                bytes = bytes,
                releaseFirstRead = release,
                firstReadStarted = firstReadStarted,
            )
            val provider = WebDavRangeProvider(
                client = client,
                path = "/books/book.cbz",
                size = bytes.size.toLong(),
                readAheadBytes = 0,
            )

            val prefetchThread = Thread {
                runCatching { provider.prefetchRange(start = 40, endInclusive = 79) }
            }
            prefetchThread.start()
            assertTrue(firstReadStarted.await(1, TimeUnit.SECONDS))

            val readResult = mutableListOf<ByteArray>()
            val readThread = Thread {
                readResult += provider.readRange(fileId = 1, start = 50, endInclusive = 59)
            }
            readThread.start()
            Thread.sleep(100)
            assertTrue("readRange should first join the covering prefetch", readThread.isAlive)

            release.complete(Unit)
            readThread.join(1_000)
            prefetchThread.join(1_000)

            assertFalse(readThread.isAlive)
            assertFalse(prefetchThread.isAlive)
            assertArrayEquals(bytes.sliceArray(50..59), readResult.single())
            assertEquals(listOf(40L to 79L, 50L to 59L), client.rangeCalls)
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
    fun prefetchedRangeCanBeCheckedAndReadFromCacheWithoutWebDavRequest() {
        val bytes = ByteArray(128) { it.toByte() }
        val client = RecordingWebDavClient(bytes)
        val provider = WebDavRangeProvider(
            client = client,
            path = "/books/book.cbz",
            size = bytes.size.toLong(),
            readAheadBytes = 0,
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
        )

        val prefetchThread = Thread {
            provider.prefetchRange(start = 40, endInclusive = 79)
        }
        prefetchThread.start()
        assertTrue(firstReadStarted.await(1, TimeUnit.SECONDS))
        val cached = provider.readCachedRange(start = 50, endInclusive = 59)

        assertNull(cached)
        assertEquals(listOf(40L to 79L), client.rangeCalls)

        release.complete(Unit)
        prefetchThread.join(1_000)
        assertFalse(prefetchThread.isAlive)
        assertArrayEquals(bytes.sliceArray(50..59), provider.readCachedRange(start = 50, endInclusive = 59))
        assertEquals(listOf(40L to 79L), client.rangeCalls)
    }

    @Test
    fun cancelPrefetchesCancelsInFlightPrefetchRequest() {
        val bytes = ByteArray(128) { it.toByte() }
        val readStarted = CountDownLatch(1)
        val cancellationRegistered = CountDownLatch(1)
        val cancellationCalled = CountDownLatch(1)
        val client = CancellableBlockingWebDavClient(
            bytes = bytes,
            readStarted = readStarted,
            cancellationRegistered = cancellationRegistered,
            cancellationCalled = cancellationCalled,
        )
        val provider = WebDavRangeProvider(
            client = client,
            path = "/books/book.cbz",
            size = bytes.size.toLong(),
            readAheadBytes = 0,
        )
        val prefetchError = AtomicReference<Throwable?>()
        val prefetchThread = Thread {
            prefetchError.set(runCatching { provider.prefetchRange(start = 40, endInclusive = 79) }.exceptionOrNull())
        }

        prefetchThread.start()
        assertTrue(readStarted.await(1, TimeUnit.SECONDS))
        assertTrue(cancellationRegistered.await(1, TimeUnit.SECONDS))

        provider.cancelPrefetches()

        assertTrue(cancellationCalled.await(1, TimeUnit.SECONDS))
        prefetchThread.join(1_000)
        assertFalse(prefetchThread.isAlive)
        assertTrue(prefetchError.get() is CancellationException)
        assertEquals(listOf(40L to 79L), client.rangeCalls)
    }

    @Test
    fun cancelPrefetchesCancelsInFlightDemandRequestForReaderClose() {
        val bytes = ByteArray(128) { it.toByte() }
        val readStarted = CountDownLatch(1)
        val cancellationRegistered = CountDownLatch(1)
        val cancellationCalled = CountDownLatch(1)
        val client = CancellableBlockingWebDavClient(
            bytes = bytes,
            readStarted = readStarted,
            cancellationRegistered = cancellationRegistered,
            cancellationCalled = cancellationCalled,
        )
        val provider = WebDavRangeProvider(
            client = client,
            path = "/books/book.cbz",
            size = bytes.size.toLong(),
            readAheadBytes = 0,
        )
        val readError = AtomicReference<Throwable?>()
        val readThread = Thread {
            readError.set(runCatching { provider.readRange(fileId = 1L, start = 40, endInclusive = 79) }.exceptionOrNull())
        }

        readThread.start()
        assertTrue(readStarted.await(1, TimeUnit.SECONDS))
        assertTrue(cancellationRegistered.await(1, TimeUnit.SECONDS))

        try {
            provider.cancelPrefetches()

            assertTrue(cancellationCalled.await(1, TimeUnit.SECONDS))
            readThread.join(1_000)
            assertFalse(readThread.isAlive)
            assertTrue(readError.get() is CancellationException)
            assertEquals(listOf(40L to 79L), client.rangeCalls)
        } finally {
            provider.close()
            readThread.join(1_000)
        }
    }

}
