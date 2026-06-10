package com.example.comicdav.network

import com.example.comicdav.nativebridge.RangeProviderRegistry
import java.io.Closeable
import java.io.File
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
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
        val client = CacheOnlyRecordingWebDavClient(bytes)
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
        val client = CacheOnlyBlockingWebDavClient(
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
    fun readRangeJoinsCoveringInFlightPrefetch() {
        val bytes = ByteArray(128) { it.toByte() }
        val release = CompletableDeferred<Unit>()
        val firstReadStarted = CountDownLatch(1)
        val client = CacheOnlyBlockingFirstRangeWebDavClient(
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
        val selectedPageBytes = mutableListOf<ByteArray>()
        val readThread = Thread {
            selectedPageBytes += provider.readRange(fileId = 1L, start = 50, endInclusive = 59)
        }
        readThread.start()
        Thread.sleep(100)

        assertTrue("selected-page read should wait for the covering prefetch", readThread.isAlive)

        release.complete(Unit)
        readThread.join(1_000)
        prefetchThread.join(1_000)
        assertFalse(readThread.isAlive)
        assertFalse(prefetchThread.isAlive)
        assertArrayEquals(bytes.sliceArray(50..59), selectedPageBytes.single())
        assertEquals(listOf(40L to 79L), client.rangeCalls)
    }

    @Test
    fun readRangeFallsBackWhenJoinedPrefetchFails() {
        val bytes = ByteArray(128) { it.toByte() }
        val release = CompletableDeferred<Unit>()
        val firstReadStarted = CountDownLatch(1)
        val client = CacheOnlyFailingFirstRangeWebDavClient(
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
            runCatching { provider.prefetchRange(start = 40, endInclusive = 79) }
        }
        prefetchThread.start()
        assertTrue(firstReadStarted.await(1, TimeUnit.SECONDS))

        val selectedPageBytes = mutableListOf<ByteArray>()
        val readThread = Thread {
            selectedPageBytes += provider.readRange(fileId = 1L, start = 50, endInclusive = 59)
        }
        readThread.start()
        Thread.sleep(100)
        assertTrue("selected-page read should first wait for the covering prefetch", readThread.isAlive)

        release.complete(Unit)
        readThread.join(1_000)
        prefetchThread.join(1_000)

        assertFalse(readThread.isAlive)
        assertFalse(prefetchThread.isAlive)
        assertArrayEquals(bytes.sliceArray(50..59), selectedPageBytes.single())
        assertEquals(listOf(40L to 79L, 50L to 59L), client.rangeCalls)
    }

    @Test
    fun cancelPrefetchesCancelsInFlightPrefetchRequest() {
        val bytes = ByteArray(128) { it.toByte() }
        val readStarted = CountDownLatch(1)
        val cancellationRegistered = CountDownLatch(1)
        val cancellationCalled = CountDownLatch(1)
        val client = CacheOnlyCancellableBlockingWebDavClient(
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
            logDiagnostic = {},
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
        val client = CacheOnlyCancellableBlockingWebDavClient(
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
            logDiagnostic = {},
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

    @Test
    fun registryExposesCacheOnlyRangeRead() {
        val bytes = ByteArray(128) { it.toByte() }
        val provider = WebDavRangeProvider(
            client = CacheOnlyRecordingWebDavClient(bytes),
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

}

private open class CacheOnlyRecordingWebDavClient(private val bytes: ByteArray) : WebDavClient {
    val rangeCalls: MutableList<Pair<Long, Long>> = Collections.synchronizedList(mutableListOf())

    override suspend fun list(path: String): List<WebDavItem> =
        error("Not used")

    override suspend fun head(path: String): RemoteFileInfo =
        error("Not used")

    override suspend fun readRange(path: String, start: Long, endInclusive: Long): ByteArray {
        recordRangeCall(start, endInclusive)
        return rangeBytes(start, endInclusive)
    }

    override suspend fun download(path: String, target: File, onBytesRead: (Long) -> Unit): Long =
        error("Not used")

    protected fun recordRangeCall(start: Long, endInclusive: Long) {
        rangeCalls += start to endInclusive
    }

    protected fun rangeBytes(start: Long, endInclusive: Long): ByteArray =
        bytes.sliceArray(start.toInt()..endInclusive.toInt())
}

private class CacheOnlyBlockingWebDavClient(
    bytes: ByteArray,
    private val release: CompletableDeferred<Unit>,
    private val firstReadStarted: CountDownLatch,
) : CacheOnlyRecordingWebDavClient(bytes) {
    override suspend fun readRange(path: String, start: Long, endInclusive: Long): ByteArray {
        recordRangeCall(start, endInclusive)
        firstReadStarted.countDown()
        release.await()
        return rangeBytes(start, endInclusive)
    }
}

private class CacheOnlyBlockingFirstRangeWebDavClient(
    bytes: ByteArray,
    private val releaseFirstRead: CompletableDeferred<Unit>,
    private val firstReadStarted: CountDownLatch,
) : CacheOnlyRecordingWebDavClient(bytes) {
    private val firstCall = AtomicBoolean(true)

    override suspend fun readRange(path: String, start: Long, endInclusive: Long): ByteArray {
        recordRangeCall(start, endInclusive)
        if (firstCall.getAndSet(false)) {
            firstReadStarted.countDown()
            releaseFirstRead.await()
        }
        return rangeBytes(start, endInclusive)
    }
}

private class CacheOnlyFailingFirstRangeWebDavClient(
    bytes: ByteArray,
    private val releaseFirstRead: CompletableDeferred<Unit>,
    private val firstReadStarted: CountDownLatch,
) : CacheOnlyRecordingWebDavClient(bytes) {
    private val firstCall = AtomicBoolean(true)

    override suspend fun readRange(path: String, start: Long, endInclusive: Long): ByteArray {
        recordRangeCall(start, endInclusive)
        if (firstCall.getAndSet(false)) {
            firstReadStarted.countDown()
            releaseFirstRead.await()
            throw IllegalStateException("first range failed")
        }
        return rangeBytes(start, endInclusive)
    }
}

private class CacheOnlyCancellableBlockingWebDavClient(
    bytes: ByteArray,
    private val readStarted: CountDownLatch,
    private val cancellationRegistered: CountDownLatch,
    private val cancellationCalled: CountDownLatch,
) : CacheOnlyRecordingWebDavClient(bytes) {
    override suspend fun openRangeStream(
        path: String,
        start: Long,
        endInclusive: Long?,
        registerCancellation: (Closeable) -> Unit,
    ): WebDavStreamResponse {
        requireNotNull(endInclusive)
        recordRangeCall(start, endInclusive)
        val cancelled = CompletableDeferred<Unit>()
        registerCancellation(
            Closeable {
                cancellationCalled.countDown()
                cancelled.complete(Unit)
            },
        )
        cancellationRegistered.countDown()
        readStarted.countDown()
        cancelled.await()
        throw CancellationException("range request cancelled")
    }
}
