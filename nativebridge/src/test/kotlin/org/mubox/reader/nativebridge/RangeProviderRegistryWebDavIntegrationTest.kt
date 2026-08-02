package org.mubox.reader.nativebridge

import org.mubox.reader.core.remote.ContentRange
import org.mubox.reader.core.remote.RemoteFileInfo
import org.mubox.reader.core.remote.WebDavClient
import org.mubox.reader.core.remote.WebDavItem
import org.mubox.reader.core.remote.WebDavStreamResponse
import org.mubox.reader.network.WebDavRangeProvider
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RangeProviderRegistryWebDavIntegrationTest {
    @Test
    fun unregisterCancelsInFlightPrefetchRequest() {
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
        val fileId = RangeProviderRegistry.register(provider)
        val prefetchThread = Thread {
            runCatching {
                RangeProviderRegistry.prefetchRange(fileId, start = 40, endInclusive = 79)
            }
        }

        prefetchThread.start()
        assertTrue(readStarted.await(1, TimeUnit.SECONDS))
        assertTrue(cancellationRegistered.await(1, TimeUnit.SECONDS))

        RangeProviderRegistry.unregister(fileId)

        assertTrue(cancellationCalled.await(1, TimeUnit.SECONDS))
        prefetchThread.join(1_000)
        assertFalse(prefetchThread.isAlive)
        assertEquals(listOf(40L to 79L), client.rangeCalls)
    }

    @Test
    fun registryExposesCacheOnlyRangeRead() {
        val bytes = ByteArray(128) { it.toByte() }
        val provider = WebDavRangeProvider(
            client = RecordingWebDavClient(bytes),
            path = "/books/book.cbz",
            size = bytes.size.toLong(),
            readAheadBytes = 0,
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

private class RecordingWebDavClient(
    private val bytes: ByteArray,
) : WebDavClient {
    override suspend fun list(path: String): List<WebDavItem> = emptyList()

    override suspend fun head(path: String): RemoteFileInfo =
        RemoteFileInfo(path, bytes.size.toLong(), etag = null, lastModified = null, supportsRange = true)

    override suspend fun readRange(path: String, start: Long, endInclusive: Long): ByteArray =
        bytes.sliceArray(start.toInt()..endInclusive.toInt())

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
        val stream = BlockingTestInputStream(readStarted)
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

private class BlockingTestInputStream(
    private val readStarted: CountDownLatch,
) : InputStream() {
    @Volatile
    private var closed = false

    override fun read(): Int {
        readStarted.countDown()
        while (!closed) {
            Thread.sleep(10)
        }
        throw IOException("stream cancelled")
    }

    override fun close() {
        closed = true
    }
}
