package org.mubox.reader.network

import java.io.ByteArrayInputStream
import java.io.Closeable
import java.io.File
import java.nio.ByteBuffer
import java.util.concurrent.CancellationException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.mubox.reader.core.remote.ContentRange
import org.mubox.reader.core.remote.RemoteFileInfo
import org.mubox.reader.core.remote.WebDavClient
import org.mubox.reader.core.remote.WebDavItem
import org.mubox.reader.core.remote.WebDavStreamResponse
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class WebDavRangeProviderTest {
    @Test
    fun fetchRangeIntoStreamsExactBytesIntoDirectBuffer() {
        val bytes = ByteArray(128) { it.toByte() }
        val client = StreamingWebDavClient(bytes)
        val provider = WebDavRangeProvider(client, "/books/book.cbz", bytes.size.toLong())
        val target = ByteBuffer.allocateDirect(20)

        val written = provider.fetchRangeInto(
            fileId = 7,
            requestId = 41,
            start = 10,
            endInclusive = 29,
            target = target,
        )

        assertEquals(20, written)
        assertEquals(20, target.position())
        assertArrayEquals(bytes.sliceArray(10..29), target.writtenBytes())
        assertEquals(listOf(10L to 29L), client.rangeCalls)
        assertEquals(1, client.cancellationRegistrations)
    }

    @Test
    fun largeStreamNeverRequestsMoreThanFixedScratchCapacity() {
        val rangeSize = 256 * 1024 + 8 * 1024
        val bytes = ByteArray(rangeSize) { (it % 251).toByte() }
        val client = StreamingWebDavClient(bytes)
        val provider = WebDavRangeProvider(client, "/books/book.cbz", bytes.size.toLong())
        val target = ByteBuffer.allocateDirect(rangeSize)

        assertEquals(
            rangeSize,
            provider.fetchRangeInto(1, 42, 0, rangeSize.toLong() - 1, target),
        )

        assertTrue(client.largestBulkRead <= 64 * 1024)
        assertEquals(64 * 1024, client.largestBulkRead)
        assertArrayEquals(bytes, target.writtenBytes())
    }

    @Test
    fun overlongStreamingResponseIsRejectedAndClosed() {
        val bytes = ByteArray(64) { it.toByte() }
        val client = StreamingWebDavClient(bytes, appendExtraResponseByte = true)
        val provider = WebDavRangeProvider(client, "/books/book.cbz", bytes.size.toLong())

        val error = assertThrows(java.io.IOException::class.java) {
            provider.fetchRangeInto(1, 43, 10, 19, ByteBuffer.allocateDirect(10))
        }

        assertTrue(error.message.orEmpty().contains("actual>expected"))
        assertEquals(1, client.closedResponses)
    }

    @Test
    fun fetchRangeIntoRejectsInvalidRequestAndTargetBeforeNetworkIo() {
        val bytes = ByteArray(64) { it.toByte() }
        val client = StreamingWebDavClient(bytes)
        val provider = WebDavRangeProvider(client, "/books/book.cbz", bytes.size.toLong())

        assertThrows(IllegalArgumentException::class.java) {
            provider.fetchRangeInto(1, 0, 10, 19, ByteBuffer.allocateDirect(10))
        }
        assertThrows(IllegalArgumentException::class.java) {
            provider.fetchRangeInto(1, 1, -1, 8, ByteBuffer.allocateDirect(10))
        }
        assertThrows(IllegalArgumentException::class.java) {
            provider.fetchRangeInto(1, 1, 20, 19, ByteBuffer.allocateDirect(0))
        }
        assertThrows(IllegalArgumentException::class.java) {
            provider.fetchRangeInto(1, 1, 60, 64, ByteBuffer.allocateDirect(5))
        }
        assertThrows(IllegalArgumentException::class.java) {
            provider.fetchRangeInto(1, 1, 10, 19, ByteBuffer.allocate(10))
        }
        assertThrows(IllegalArgumentException::class.java) {
            provider.fetchRangeInto(1, 1, 10, 19, ByteBuffer.allocateDirect(10).asReadOnlyBuffer())
        }
        assertThrows(IllegalArgumentException::class.java) {
            provider.fetchRangeInto(1, 1, 10, 19, ByteBuffer.allocateDirect(9))
        }
        val offsetTarget = ByteBuffer.allocateDirect(12).apply {
            position(1)
            limit(11)
        }
        assertThrows(IllegalArgumentException::class.java) {
            provider.fetchRangeInto(1, 1, 10, 19, offsetTarget)
        }
        val wrongSizedSlice = ByteBuffer.allocateDirect(12).apply { position(1) }.slice()
        assertThrows(IllegalArgumentException::class.java) {
            provider.fetchRangeInto(1, 1, 10, 19, wrongSizedSlice)
        }

        assertTrue(client.rangeCalls.isEmpty())
    }

    @Test
    fun truncatedStreamingResponseFailsAndReleasesRequestId() {
        val bytes = ByteArray(64) { it.toByte() }
        val client = TruncatedStreamingWebDavClient(bytes)
        val provider = WebDavRangeProvider(client, "/books/book.cbz", bytes.size.toLong())

        val firstError = assertThrows(java.io.IOException::class.java) {
            provider.fetchRangeInto(1, 9, 10, 19, ByteBuffer.allocateDirect(10))
        }
        assertTrue(firstError.message.orEmpty().contains("expected=10 actual=9"))

        assertThrows(java.io.IOException::class.java) {
            provider.fetchRangeInto(1, 9, 10, 19, ByteBuffer.allocateDirect(10))
        }
        assertEquals(listOf(10L to 19L, 10L to 19L), client.rangeCalls)
    }

    @Test
    fun cancellationBeforeRegistrationIsConsumedByOneMatchingRequest() {
        val bytes = ByteArray(64) { it.toByte() }
        val client = StreamingWebDavClient(bytes)
        val provider = WebDavRangeProvider(client, "/books/book.cbz", bytes.size.toLong())
        provider.cancelRangeRequest(17)

        assertThrows(CancellationException::class.java) {
            provider.fetchRangeInto(1, 17, 10, 19, ByteBuffer.allocateDirect(10))
        }

        val target = ByteBuffer.allocateDirect(10)
        assertEquals(10, provider.fetchRangeInto(1, 17, 10, 19, target))
        assertArrayEquals(bytes.sliceArray(10..19), target.writtenBytes())
        assertEquals(listOf(10L to 19L), client.rangeCalls)
    }

    @Test
    fun cancellationAfterCompletedRequestDoesNotCreateRegistrationTombstone() {
        val bytes = ByteArray(64) { it.toByte() }
        val client = StreamingWebDavClient(bytes)
        val provider = WebDavRangeProvider(client, "/books/book.cbz", bytes.size.toLong())

        assertEquals(
            10,
            provider.fetchRangeInto(1, 18, 10, 19, ByteBuffer.allocateDirect(10)),
        )
        provider.cancelRangeRequest(18)

        val target = ByteBuffer.allocateDirect(10)
        assertEquals(10, provider.fetchRangeInto(1, 18, 20, 29, target))
        assertArrayEquals(bytes.sliceArray(20..29), target.writtenBytes())
        assertEquals(listOf(10L to 19L, 20L to 29L), client.rangeCalls)
    }

    @Test
    fun cancellationClosesActiveNetworkRequestAndSurfacesCancellation() {
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
        val provider = WebDavRangeProvider(client, "/books/book.cbz", bytes.size.toLong())
        val failure = AtomicReference<Throwable?>()
        val request = Thread {
            failure.set(
                runCatching {
                    provider.fetchRangeInto(1, 23, 40, 79, ByteBuffer.allocateDirect(40))
                }.exceptionOrNull(),
            )
        }

        request.start()
        assertTrue(readStarted.await(1, TimeUnit.SECONDS))
        assertTrue(cancellationRegistered.await(1, TimeUnit.SECONDS))
        provider.cancelRangeRequest(23)

        assertTrue(cancellationCalled.await(1, TimeUnit.SECONDS))
        request.join(1_000)
        assertFalse(request.isAlive)
        assertTrue(failure.get() is CancellationException)
        assertTrue(failure.get()?.cause is java.io.IOException)
        assertEquals(listOf(40L to 79L), client.rangeCalls)
    }

    @Test
    fun cancelRangeRequestDoesNotCloseProvider() {
        val bytes = ByteArray(128) { it.toByte() }
        val readStarted = CountDownLatch(1)
        val cancellationRegistered = CountDownLatch(1)
        val cancellationCalled = CountDownLatch(1)
        val client = CancellableFirstThenStreamingWebDavClient(
            bytes = bytes,
            readStarted = readStarted,
            cancellationRegistered = cancellationRegistered,
            cancellationCalled = cancellationCalled,
        )
        val provider = WebDavRangeProvider(client, "/books/book.cbz", bytes.size.toLong())
        val request = Thread {
            runCatching {
                provider.fetchRangeInto(1, 24, 40, 79, ByteBuffer.allocateDirect(40))
            }
        }

        request.start()
        assertTrue(readStarted.await(1, TimeUnit.SECONDS))
        assertTrue(cancellationRegistered.await(1, TimeUnit.SECONDS))
        provider.cancelRangeRequest(24)

        assertTrue(cancellationCalled.await(1, TimeUnit.SECONDS))
        request.join(1_000)
        assertFalse(request.isAlive)

        // Per-request cancellation must not close the provider.
        val target = ByteBuffer.allocateDirect(4)
        assertEquals(4, provider.fetchRangeInto(1, 25, 0, 3, target))
        assertArrayEquals(bytes.sliceArray(0..3), target.writtenBytes())
    }

    @Test
    fun closeCancelsActiveRequestAndRejectsFutureRequests() {
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
        val provider = WebDavRangeProvider(client, "/books/book.cbz", bytes.size.toLong())
        val request = Thread {
            runCatching {
                provider.fetchRangeInto(1, 31, 40, 79, ByteBuffer.allocateDirect(40))
            }
        }

        request.start()
        assertTrue(readStarted.await(1, TimeUnit.SECONDS))
        assertTrue(cancellationRegistered.await(1, TimeUnit.SECONDS))
        provider.close()

        assertTrue(cancellationCalled.await(1, TimeUnit.SECONDS))
        request.join(1_000)
        assertFalse(request.isAlive)
        assertThrows(CancellationException::class.java) {
            provider.fetchRangeInto(1, 32, 0, 3, ByteBuffer.allocateDirect(4))
        }
    }
}

private fun ByteBuffer.writtenBytes(): ByteArray {
    flip()
    return ByteArray(remaining()).also(::get)
}

private class StreamingWebDavClient(
    private val bytes: ByteArray,
    private val appendExtraResponseByte: Boolean = false,
) : WebDavClient {
    val rangeCalls = mutableListOf<Pair<Long, Long>>()
    var cancellationRegistrations = 0
    var largestBulkRead = 0
    var closedResponses = 0

    override suspend fun list(path: String): List<WebDavItem> = emptyList()

    override suspend fun head(path: String): RemoteFileInfo =
        RemoteFileInfo(path, bytes.size.toLong(), null, null, supportsRange = true)

    override suspend fun readRange(path: String, start: Long, endInclusive: Long): ByteArray =
        error("provider should use range streams")

    override suspend fun openRangeStream(
        path: String,
        start: Long,
        endInclusive: Long?,
        registerCancellation: (Closeable) -> Unit,
    ): WebDavStreamResponse {
        val end = requireNotNull(endInclusive)
        rangeCalls += start to end
        val requested = bytes.sliceArray(start.toInt()..end.toInt())
        val payload = if (appendExtraResponseByte) requested + 0x7f.toByte() else requested
        val stream = TrackingInputStream(
            payload,
            onBulkRead = { requestedBytes ->
                largestBulkRead = maxOf(largestBulkRead, requestedBytes)
            },
            onClose = { closedResponses += 1 },
        )
        registerCancellation(stream)
        cancellationRegistrations += 1
        return WebDavStreamResponse(
            stream = stream,
            statusCode = 206,
            contentLength = end - start + 1,
            contentRange = ContentRange(start, end, bytes.size.toLong()),
            contentType = "application/octet-stream",
            totalSize = bytes.size.toLong(),
            close = stream::close,
        )
    }

    override suspend fun download(path: String, target: File, onBytesRead: (Long) -> Unit): Long =
        error("unused")
}

private class TrackingInputStream(
    bytes: ByteArray,
    private val onBulkRead: (Int) -> Unit,
    private val onClose: () -> Unit,
) : ByteArrayInputStream(bytes) {
    private var closeReported = false

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        onBulkRead(length)
        return super.read(buffer, offset, length)
    }

    override fun close() {
        if (!closeReported) {
            closeReported = true
            onClose()
        }
        super.close()
    }
}

private class CancellableFirstThenStreamingWebDavClient(
    private val bytes: ByteArray,
    private val readStarted: CountDownLatch,
    private val cancellationRegistered: CountDownLatch,
    private val cancellationCalled: CountDownLatch,
) : WebDavClient {
    private var requestCount = 0

    override suspend fun list(path: String): List<WebDavItem> = emptyList()

    override suspend fun head(path: String): RemoteFileInfo =
        RemoteFileInfo(path, bytes.size.toLong(), null, null, supportsRange = true)

    override suspend fun readRange(path: String, start: Long, endInclusive: Long): ByteArray =
        error("provider should use range streams")

    override suspend fun openRangeStream(
        path: String,
        start: Long,
        endInclusive: Long?,
        registerCancellation: (Closeable) -> Unit,
    ): WebDavStreamResponse {
        val end = requireNotNull(endInclusive)
        requestCount += 1
        val stream = if (requestCount == 1) {
            BlockingTestInputStream(readStarted).also { blocking ->
                registerCancellation(
                    Closeable {
                        cancellationCalled.countDown()
                        blocking.close()
                    },
                )
                cancellationRegistered.countDown()
            }
        } else {
            ByteArrayInputStream(bytes.sliceArray(start.toInt()..end.toInt())).also(registerCancellation)
        }
        return WebDavStreamResponse(
            stream = stream,
            statusCode = 206,
            contentLength = end - start + 1,
            contentRange = ContentRange(start, end, bytes.size.toLong()),
            contentType = "application/octet-stream",
            totalSize = bytes.size.toLong(),
            close = stream::close,
        )
    }

    override suspend fun download(path: String, target: File, onBytesRead: (Long) -> Unit): Long =
        error("unused")
}
