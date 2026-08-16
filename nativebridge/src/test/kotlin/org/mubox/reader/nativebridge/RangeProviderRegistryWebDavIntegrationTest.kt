package org.mubox.reader.nativebridge

import java.io.ByteArrayInputStream
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.io.InputStream
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
import org.mubox.reader.network.WebDavRangeProvider
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class RangeProviderRegistryWebDavIntegrationTest {
    @Test
    fun v1CallbackStreamsWebDavRangeIntoNativeOwnedBuffer() {
        val bytes = ByteArray(128) { (it * 5).toByte() }
        val client = StreamingWebDavClient(bytes)
        val provider = WebDavRangeProvider(client, "/books/book.cbz", bytes.size.toLong())
        val fileId = RangeProviderRegistry.register(provider)
        val target = ByteBuffer.allocateDirect(40)

        try {
            val written = RangeProviderRegistry.fetchRangeIntoV1(
                fileId = fileId,
                requestId = 101,
                start = 40,
                endInclusive = 79,
                target = target,
            )

            assertEquals(40, written)
            target.flip()
            assertArrayEquals(bytes.sliceArray(40..79), ByteArray(40).also(target::get))
            assertEquals(listOf(40L to 79L), client.rangeCalls)
        } finally {
            RangeProviderRegistry.unregister(fileId)
        }
    }

    @Test
    fun v1CancelCallbackStopsOnlyTheMatchingInFlightFetch() {
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
        val fileId = RangeProviderRegistry.register(provider)
        val failure = AtomicReference<Throwable?>()
        val fetchThread = Thread {
            failure.set(
                runCatching {
                    RangeProviderRegistry.fetchRangeIntoV1(
                        fileId,
                        202,
                        40,
                        79,
                        ByteBuffer.allocateDirect(40),
                    )
                }.exceptionOrNull(),
            )
        }

        try {
            fetchThread.start()
            assertTrue(readStarted.await(1, TimeUnit.SECONDS))
            assertTrue(cancellationRegistered.await(1, TimeUnit.SECONDS))

            RangeProviderRegistry.cancelRangeFetchV1(fileId, 999)
            assertFalse(cancellationCalled.await(100, TimeUnit.MILLISECONDS))
            RangeProviderRegistry.cancelRangeFetchV1(fileId, 202)

            assertTrue(cancellationCalled.await(1, TimeUnit.SECONDS))
            fetchThread.join(1_000)
            assertFalse(fetchThread.isAlive)
            assertTrue(failure.get() is CancellationException)
            assertEquals(listOf(40L to 79L), client.rangeCalls)
        } finally {
            RangeProviderRegistry.unregister(fileId)
            fetchThread.join(1_000)
        }
    }

    @Test
    fun unregisterClosesProviderCancelsFetchAndRemovesCallbackTarget() {
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
        val fileId = RangeProviderRegistry.register(
            WebDavRangeProvider(client, "/books/book.cbz", bytes.size.toLong()),
        )
        val fetchThread = Thread {
            runCatching {
                RangeProviderRegistry.fetchRangeIntoV1(
                    fileId,
                    303,
                    40,
                    79,
                    ByteBuffer.allocateDirect(40),
                )
            }
        }

        fetchThread.start()
        assertTrue(readStarted.await(1, TimeUnit.SECONDS))
        assertTrue(cancellationRegistered.await(1, TimeUnit.SECONDS))
        RangeProviderRegistry.unregister(fileId)

        assertTrue(cancellationCalled.await(1, TimeUnit.SECONDS))
        fetchThread.join(1_000)
        assertFalse(fetchThread.isAlive)
        assertThrows(IllegalArgumentException::class.java) {
            RangeProviderRegistry.fetchRangeIntoV1(
                fileId,
                304,
                0,
                3,
                ByteBuffer.allocateDirect(4),
            )
        }
    }
}

private class StreamingWebDavClient(private val bytes: ByteArray) : WebDavClient {
    val rangeCalls = mutableListOf<Pair<Long, Long>>()

    override suspend fun list(path: String): List<WebDavItem> = emptyList()

    override suspend fun head(path: String): RemoteFileInfo =
        RemoteFileInfo(path, bytes.size.toLong(), etag = null, lastModified = null, supportsRange = true)

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
        val stream = ByteArrayInputStream(bytes.sliceArray(start.toInt()..end.toInt()))
        registerCancellation(stream)
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

private class CancellableBlockingWebDavClient(
    private val bytes: ByteArray,
    private val readStarted: CountDownLatch,
    private val cancellationRegistered: CountDownLatch,
    private val cancellationCalled: CountDownLatch,
) : WebDavClient {
    val rangeCalls = mutableListOf<Pair<Long, Long>>()

    override suspend fun list(path: String): List<WebDavItem> = emptyList()

    override suspend fun head(path: String): RemoteFileInfo =
        RemoteFileInfo(path, bytes.size.toLong(), etag = null, lastModified = null, supportsRange = true)

    override suspend fun readRange(path: String, start: Long, endInclusive: Long): ByteArray =
        error("provider should use cancellable range streams")

    override suspend fun openRangeStream(
        path: String,
        start: Long,
        endInclusive: Long?,
        registerCancellation: (Closeable) -> Unit,
    ): WebDavStreamResponse {
        val end = requireNotNull(endInclusive)
        rangeCalls += start to end
        val stream = BlockingInputStream(readStarted)
        registerCancellation(
            Closeable {
                cancellationCalled.countDown()
                stream.close()
            },
        )
        cancellationRegistered.countDown()
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

private class BlockingInputStream(
    private val readStarted: CountDownLatch,
) : InputStream() {
    @Volatile
    private var closed = false

    override fun read(): Int {
        readStarted.countDown()
        while (!closed) Thread.sleep(10)
        throw IOException("stream cancelled")
    }

    override fun close() {
        closed = true
    }
}
