package com.example.comicdav.video.proxy

import com.example.comicdav.network.ContentRange
import com.example.comicdav.network.RemoteFileInfo
import com.example.comicdav.network.WebDavClient
import com.example.comicdav.network.WebDavItem
import com.example.comicdav.network.WebDavStreamResponse
import java.io.ByteArrayInputStream
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoSeekOptimizerTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @After
    fun tearDown() {
        scope.cancel()
    }

    @Test
    fun openRangeReturnsExactSliceFromAlignedSegments() = runTest {
        val bytes = numberedBytes(24)
        val client = RecordingClient(bytes)
        val optimizer = VideoSeekOptimizer(coroutineScope = scope, segmentBytes = 8)

        val response = optimizer.openRangeStream(
            client = client,
            request = request(size = bytes.size.toLong()),
            totalSize = bytes.size.toLong(),
            start = 6L,
            endInclusive = 17L,
            settings = VideoProxySettings.DEFAULT.copy(forwardPrefetchMode = VideoForwardPrefetchMode.OFF),
        )

        assertEquals(206, response.statusCode)
        assertEquals(12L, response.contentLength)
        assertEquals(ContentRange(6L, 17L, 24L), response.contentRange)
        assertEquals("video/mp4", response.contentType)
        assertEquals(24L, response.totalSize)
        assertArrayEquals(bytes.copyOfRange(6, 18), response.stream.readBytes())
        assertEquals(listOf(0L to 7L, 8L to 15L, 16L to 23L), client.openRangeCalls)
        assertEquals(3, client.closedResponses.get())
    }

    @Test
    fun cachedSegmentAvoidsSecondRemoteFetch() = runTest {
        val bytes = numberedBytes(16)
        val client = RecordingClient(bytes)
        val optimizer = VideoSeekOptimizer(coroutineScope = scope, segmentBytes = 8)
        val req = request(size = bytes.size.toLong())
        val settings = VideoProxySettings.DEFAULT.copy(forwardPrefetchMode = VideoForwardPrefetchMode.OFF)

        optimizer.openRangeStream(client, req, bytes.size.toLong(), 0L, 3L, settings).close()
        val second = optimizer.openRangeStream(client, req, bytes.size.toLong(), 4L, 7L, settings)

        assertArrayEquals(bytes.copyOfRange(4, 8), second.stream.readBytes())
        assertEquals(listOf(0L to 7L), client.openRangeCalls)
    }

    @Test
    fun disabledSeekOptimizationBypassesSegmentCacheAndFetchesExactRange() = runTest {
        val bytes = numberedBytes(16)
        val client = RecordingClient(bytes)
        val optimizer = VideoSeekOptimizer(coroutineScope = scope, segmentBytes = 8)
        val req = request(size = bytes.size.toLong())
        val settings = VideoProxySettings.DEFAULT.copy(
            seekOptimizationEnabled = false,
            forwardPrefetchMode = VideoForwardPrefetchMode.STANDARD,
        )

        val first = optimizer.openRangeStream(client, req, bytes.size.toLong(), 2L, 4L, settings)
        val second = optimizer.openRangeStream(client, req, bytes.size.toLong(), 2L, 4L, settings)

        assertArrayEquals(bytes.copyOfRange(2, 5), first.stream.readBytes())
        assertArrayEquals(bytes.copyOfRange(2, 5), second.stream.readBytes())
        assertEquals(listOf(2L to 4L, 2L to 4L), client.openRangeCalls)
    }

    @Test
    fun concurrentSameSegmentRequestsShareOneRemoteFetch() = runTest {
        val bytes = numberedBytes(16)
        val gate = CompletableDeferred<Unit>()
        val client = BlockingRecordingClient(bytes, gate)
        val optimizer = VideoSeekOptimizer(coroutineScope = scope, segmentBytes = 8)
        val req = request(size = bytes.size.toLong())
        val settings = VideoProxySettings.DEFAULT.copy(forwardPrefetchMode = VideoForwardPrefetchMode.OFF)
        val requestStarted = AtomicInteger(0)

        val first = async(Dispatchers.IO) {
            requestStarted.incrementAndGet()
            optimizer.openRangeStream(client, req, bytes.size.toLong(), 0L, 3L, settings).stream.readBytes()
        }
        val second = async(Dispatchers.IO) {
            requestStarted.incrementAndGet()
            optimizer.openRangeStream(client, req, bytes.size.toLong(), 4L, 7L, settings).stream.readBytes()
        }

        eventually { assertEquals(2, requestStarted.get()) }
        eventually { assertEquals(1, client.started.get()) }
        delay(100)
        assertEquals(1, client.started.get())
        gate.complete(Unit)

        val results = listOf(first, second).awaitAll()
        assertArrayEquals(bytes.copyOfRange(0, 4), results[0])
        assertArrayEquals(bytes.copyOfRange(4, 8), results[1])
        assertEquals(listOf(0L to 7L), client.openRangeCalls)
    }

    @Test
    fun standardPrefetchFetchesNextSegmentInBackground() = runTest {
        val bytes = numberedBytes(24)
        val client = RecordingClient(bytes)
        val optimizer = VideoSeekOptimizer(coroutineScope = scope, segmentBytes = 8)

        optimizer.openRangeStream(
            client = client,
            request = request(size = bytes.size.toLong()),
            totalSize = bytes.size.toLong(),
            start = 0L,
            endInclusive = 3L,
            settings = VideoProxySettings.DEFAULT.copy(forwardPrefetchMode = VideoForwardPrefetchMode.STANDARD),
        ).close()

        eventually {
            assertEquals(listOf(0L to 7L, 8L to 15L), client.openRangeCalls)
        }
    }

    @Test
    fun aggressivePrefetchFetchesTwoForwardSegmentsInBackground() = runTest {
        val bytes = numberedBytes(32)
        val client = RecordingClient(bytes)
        val optimizer = VideoSeekOptimizer(coroutineScope = scope, segmentBytes = 8)

        optimizer.openRangeStream(
            client = client,
            request = request(size = bytes.size.toLong()),
            totalSize = bytes.size.toLong(),
            start = 0L,
            endInclusive = 3L,
            settings = VideoProxySettings.DEFAULT.copy(forwardPrefetchMode = VideoForwardPrefetchMode.AGGRESSIVE),
        ).close()

        eventually {
            assertEquals(setOf(0L to 7L, 8L to 15L, 16L to 23L), client.openRangeCalls.toSet())
        }
    }

    @Test
    fun offPrefetchSchedulesNothingBeyondForegroundSegments() = runTest {
        val bytes = numberedBytes(24)
        val client = RecordingClient(bytes)
        val optimizer = VideoSeekOptimizer(coroutineScope = scope, segmentBytes = 8)

        optimizer.openRangeStream(
            client = client,
            request = request(size = bytes.size.toLong()),
            totalSize = bytes.size.toLong(),
            start = 0L,
            endInclusive = 3L,
            settings = VideoProxySettings.DEFAULT.copy(forwardPrefetchMode = VideoForwardPrefetchMode.OFF),
        ).close()

        delay(100)
        assertEquals(listOf(0L to 7L), client.openRangeCalls)
    }

    @Test
    fun removeStreamDropsCacheAndCancelsFutureReuseForThatStreamOnly() = runTest {
        val bytes = numberedBytes(16)
        val client = RecordingClient(bytes)
        val optimizer = VideoSeekOptimizer(coroutineScope = scope, segmentBytes = 8)
        val settings = VideoProxySettings.DEFAULT.copy(forwardPrefetchMode = VideoForwardPrefetchMode.OFF)

        optimizer.openRangeStream(client, request("stream-1", bytes.size.toLong()), bytes.size.toLong(), 0L, 3L, settings).close()
        optimizer.openRangeStream(client, request("stream-2", bytes.size.toLong()), bytes.size.toLong(), 0L, 3L, settings).close()

        optimizer.removeStream("stream-1")
        optimizer.openRangeStream(client, request("stream-1", bytes.size.toLong()), bytes.size.toLong(), 0L, 3L, settings).close()
        optimizer.openRangeStream(client, request("stream-2", bytes.size.toLong()), bytes.size.toLong(), 0L, 3L, settings).close()

        assertEquals(listOf(0L to 7L, 0L to 7L, 0L to 7L), client.openRangeCalls)
    }

    @Test
    fun foregroundFetchFailureIsPropagated() = runTest {
        val optimizer = VideoSeekOptimizer(coroutineScope = scope, segmentBytes = 8)
        val client = FailingRangeClient()

        val result = runCatching {
            optimizer.openRangeStream(
                client = client,
                request = request(size = 16L),
                totalSize = 16L,
                start = 0L,
                endInclusive = 3L,
                settings = VideoProxySettings.DEFAULT.copy(forwardPrefetchMode = VideoForwardPrefetchMode.OFF),
            )
        }

        assertTrue(result.isFailure)
        assertEquals("range failed", result.exceptionOrNull()?.message)
    }

    @Test
    fun cancellingLastAwaiterDoesNotCloseSharedRemoteResponse() = runTest {
        val client = BlockingReadClient()
        val optimizer = VideoSeekOptimizer(coroutineScope = scope, segmentBytes = 8)
        val settings = VideoProxySettings.DEFAULT.copy(forwardPrefetchMode = VideoForwardPrefetchMode.OFF)
        val job = async(Dispatchers.IO) {
            optimizer.openRangeStream(
                client = client,
                request = request(size = 16L),
                totalSize = 16L,
                start = 0L,
                endInclusive = 3L,
                settings = settings,
            )
        }

        try {
            assertTrue("remote response should start reading", client.readStarted.await(2, TimeUnit.SECONDS))

            job.cancelAndJoin()

            assertFalse("awaiter cancellation should not close shared remote response", client.closed.await(100, TimeUnit.MILLISECONDS))
            optimizer.close()
            assertTrue("optimizer close should still close the remote response", client.closed.await(2, TimeUnit.SECONDS))
        } finally {
            client.forceClose()
            optimizer.close()
        }
    }

    @Test
    fun cancellingAwaiterDoesNotCancelSharedInFlightSegmentForLaterJoiners() = runTest {
        val bytes = numberedBytes(8)
        val client = GateSegmentClient(bytes)
        val optimizer = VideoSeekOptimizer(coroutineScope = scope, segmentBytes = 8)
        val settings = VideoProxySettings.DEFAULT.copy(forwardPrefetchMode = VideoForwardPrefetchMode.OFF)
        val first = async(Dispatchers.IO) {
            optimizer.openRangeStream(
                client = client,
                request = request(size = bytes.size.toLong()),
                totalSize = bytes.size.toLong(),
                start = 0L,
                endInclusive = 3L,
                settings = settings,
            )
        }

        try {
            assertTrue("first fetch should start reading", client.readStarted.await(2, TimeUnit.SECONDS))

            first.cancelAndJoin()

            val second = async(Dispatchers.IO) {
                optimizer.openRangeStream(
                    client = client,
                    request = request(size = bytes.size.toLong()),
                    totalSize = bytes.size.toLong(),
                    start = 4L,
                    endInclusive = 7L,
                    settings = settings,
                ).stream.readBytes()
            }

            delay(100)
            assertEquals("second request should join existing in-flight segment", 1, client.opened.get())
            assertEquals("awaiter cancellation must not close shared remote response", 0, client.closed.get())

            client.release()

            assertArrayEquals(bytes.copyOfRange(4, 8), second.await())
            assertEquals(listOf(0L to 7L), client.openRangeCalls)
        } finally {
            client.release()
            optimizer.close()
        }
    }

    @Test
    fun removeStreamCancelsRemoteRequestBeforeResponseIsReturned() = runTest {
        val client = PreResponseBlockingClient()
        val optimizer = VideoSeekOptimizer(coroutineScope = scope, segmentBytes = 8)
        val settings = VideoProxySettings.DEFAULT.copy(forwardPrefetchMode = VideoForwardPrefetchMode.OFF)
        val job = async(Dispatchers.IO) {
            runCatching {
                optimizer.openRangeStream(
                    client = client,
                    request = request(size = 16L),
                    totalSize = 16L,
                    start = 0L,
                    endInclusive = 3L,
                    settings = settings,
                )
            }
        }

        try {
            assertTrue("remote call cancel hook should be registered", client.cancelHookRegistered.await(2, TimeUnit.SECONDS))

            optimizer.removeStream("stream-1")

            assertTrue("removeStream should cancel the remote call before response metadata returns", client.cancelled.await(2, TimeUnit.SECONDS))
        } finally {
            client.release()
            job.cancelAndJoin()
            optimizer.close()
        }
    }

    @Test
    fun removeStreamClosesActiveRemoteResponse() = runTest {
        val client = BlockingReadClient()
        val optimizer = VideoSeekOptimizer(coroutineScope = scope, segmentBytes = 8)
        val settings = VideoProxySettings.DEFAULT.copy(forwardPrefetchMode = VideoForwardPrefetchMode.OFF)
        val job = async(Dispatchers.IO) {
            runCatching {
                optimizer.openRangeStream(
                    client = client,
                    request = request(size = 16L),
                    totalSize = 16L,
                    start = 0L,
                    endInclusive = 3L,
                    settings = settings,
                )
            }
        }

        try {
            assertTrue("remote response should start reading", client.readStarted.await(2, TimeUnit.SECONDS))

            optimizer.removeStream("stream-1")

            assertTrue("removeStream should close active remote response", client.closed.await(2, TimeUnit.SECONDS))
        } finally {
            client.forceClose()
            job.cancelAndJoin()
            optimizer.close()
        }
    }

    @Test
    fun closeClosesActiveRemoteResponse() = runTest {
        val client = BlockingReadClient()
        val optimizer = VideoSeekOptimizer(coroutineScope = scope, segmentBytes = 8)
        val settings = VideoProxySettings.DEFAULT.copy(forwardPrefetchMode = VideoForwardPrefetchMode.OFF)
        val job = async(Dispatchers.IO) {
            runCatching {
                optimizer.openRangeStream(
                    client = client,
                    request = request(size = 16L),
                    totalSize = 16L,
                    start = 0L,
                    endInclusive = 3L,
                    settings = settings,
                )
            }
        }

        try {
            assertTrue("remote response should start reading", client.readStarted.await(2, TimeUnit.SECONDS))

            optimizer.close()

            assertTrue("close should close active remote response", client.closed.await(2, TimeUnit.SECONDS))
        } finally {
            client.forceClose()
            job.cancelAndJoin()
            optimizer.close()
        }
    }

    @Test
    fun diagnosticsAreGatedAndUseRedactedStreamIds() {
        val offMessages = mutableListOf<String>()
        val off = VideoProxyDiagnostics(VideoProxyDiagnosticsMode.OFF, sink = offMessages::add)
        off.summary { "cache_hit stream=${off.streamId("secret-stream")}" }
        off.detail { "detail" }
        assertEquals(emptyList<String>(), offMessages)

        val summaryMessages = mutableListOf<String>()
        val summary = VideoProxyDiagnostics(VideoProxyDiagnosticsMode.SUMMARY, sink = summaryMessages::add)
        val redactedStreamId = summary.streamId("secret-stream")
        summary.summary { "cache_hit stream=$redactedStreamId" }
        summary.detail { "range=0-7" }

        assertEquals(1, summaryMessages.size)
        assertTrue(summaryMessages.single().contains("cache_hit"))
        assertTrue(summaryMessages.single().contains(redactedStreamId))
        assertFalse(summaryMessages.single().contains("secret-stream"))
        assertFalse(redactedStreamId.contains("secret-stream"))
    }

    private fun numberedBytes(size: Int): ByteArray =
        ByteArray(size) { it.toByte() }

    private fun request(
        streamId: String = "stream-1",
        size: Long,
    ): VideoStreamRequest =
        VideoStreamRequest(
            streamId = streamId,
            accountId = "account-1",
            remotePath = "/movie.mp4",
            displayName = "movie.mp4",
            size = size,
            etag = null,
            lastModified = null,
            mimeType = "video/mp4",
            proxySettings = VideoProxySettings.DEFAULT,
        )

    private suspend fun eventually(assertion: () -> Unit) {
        val deadline = System.currentTimeMillis() + 2_000
        var lastError: AssertionError? = null
        while (System.currentTimeMillis() < deadline) {
            try {
                assertion()
                return
            } catch (error: AssertionError) {
                lastError = error
                delay(20)
            }
        }
        throw lastError ?: AssertionError("condition was not met")
    }

    private open class RecordingClient(private val bytes: ByteArray) : WebDavClient {
        val openRangeCalls: MutableList<Pair<Long, Long>> = Collections.synchronizedList(mutableListOf())
        val closedResponses = AtomicInteger(0)

        override suspend fun list(path: String): List<WebDavItem> = emptyList()

        override suspend fun head(path: String): RemoteFileInfo =
            RemoteFileInfo(path, bytes.size.toLong(), etag = null, lastModified = null, supportsRange = true)

        override suspend fun readRange(path: String, start: Long, endInclusive: Long): ByteArray =
            bytes.copyOfRange(start.toInt(), endInclusive.toInt() + 1)

        override suspend fun openRangeStream(path: String, start: Long, endInclusive: Long?): WebDavStreamResponse {
            val end = requireNotNull(endInclusive)
            openRangeCalls += start to end
            val chunk = readRange(path, start, end)
            return WebDavStreamResponse(
                stream = ByteArrayInputStream(chunk),
                statusCode = 206,
                contentLength = chunk.size.toLong(),
                contentRange = ContentRange(start, end, bytes.size.toLong()),
                contentType = "video/mp4",
                totalSize = bytes.size.toLong(),
                close = { closedResponses.incrementAndGet() },
            )
        }

        override suspend fun download(path: String, target: File, onBytesRead: (Long) -> Unit): Long =
            error("not used")
    }

    private class BlockingRecordingClient(
        bytes: ByteArray,
        private val gate: CompletableDeferred<Unit>,
    ) : RecordingClient(bytes) {
        val started = AtomicInteger(0)

        override suspend fun openRangeStream(path: String, start: Long, endInclusive: Long?): WebDavStreamResponse {
            started.incrementAndGet()
            gate.await()
            return super.openRangeStream(path, start, endInclusive)
        }
    }

    private class FailingRangeClient : WebDavClient {
        override suspend fun list(path: String): List<WebDavItem> = emptyList()

        override suspend fun head(path: String): RemoteFileInfo =
            RemoteFileInfo(path, 16L, etag = null, lastModified = null, supportsRange = true)

        override suspend fun readRange(path: String, start: Long, endInclusive: Long): ByteArray =
            throw IllegalStateException("range failed")

        override suspend fun openRangeStream(path: String, start: Long, endInclusive: Long?): WebDavStreamResponse =
            throw IllegalStateException("range failed")

        override suspend fun download(path: String, target: File, onBytesRead: (Long) -> Unit): Long =
            error("not used")
    }

    private class BlockingReadClient : WebDavClient {
        val readStarted = CountDownLatch(1)
        val closed = CountDownLatch(1)
        private val input = BlockingInputStream(readStarted, closed)

        override suspend fun list(path: String): List<WebDavItem> = emptyList()

        override suspend fun head(path: String): RemoteFileInfo =
            RemoteFileInfo(path, 16L, etag = null, lastModified = null, supportsRange = true)

        override suspend fun readRange(path: String, start: Long, endInclusive: Long): ByteArray =
            error("not used")

        override suspend fun openRangeStream(path: String, start: Long, endInclusive: Long?): WebDavStreamResponse =
            WebDavStreamResponse(
                stream = input,
                statusCode = 206,
                contentLength = endInclusive!! - start + 1L,
                contentRange = ContentRange(start, endInclusive, 16L),
                contentType = "video/mp4",
                totalSize = 16L,
                close = input::close,
            )

        override suspend fun download(path: String, target: File, onBytesRead: (Long) -> Unit): Long =
            error("not used")

        fun forceClose() {
            input.close()
        }
    }

    private class BlockingInputStream(
        private val readStarted: CountDownLatch,
        private val closed: CountDownLatch,
    ) : InputStream() {
        @Volatile
        private var isClosed = false

        override fun read(): Int {
            readStarted.countDown()
            while (!isClosed) {
                Thread.sleep(10)
            }
            return -1
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int = read()

        override fun close() {
            isClosed = true
            closed.countDown()
        }
    }

    private class GateSegmentClient(
        private val bytes: ByteArray,
    ) : WebDavClient {
        val openRangeCalls: MutableList<Pair<Long, Long>> = Collections.synchronizedList(mutableListOf())
        val opened = AtomicInteger(0)
        val closed = AtomicInteger(0)
        val readStarted = CountDownLatch(1)
        private val released = CountDownLatch(1)

        override suspend fun list(path: String): List<WebDavItem> = emptyList()

        override suspend fun head(path: String): RemoteFileInfo =
            RemoteFileInfo(path, bytes.size.toLong(), etag = null, lastModified = null, supportsRange = true)

        override suspend fun readRange(path: String, start: Long, endInclusive: Long): ByteArray =
            error("not used")

        override suspend fun openRangeStream(path: String, start: Long, endInclusive: Long?): WebDavStreamResponse {
            val end = requireNotNull(endInclusive)
            opened.incrementAndGet()
            openRangeCalls += start to end
            val stream = object : InputStream() {
                private var offset = 0
                private var isClosed = false

                override fun read(buffer: ByteArray, off: Int, len: Int): Int {
                    readStarted.countDown()
                    released.await(2, TimeUnit.SECONDS)
                    if (isClosed) throw IOException("closed")
                    if (offset >= bytes.size) return -1
                    val count = minOf(len, bytes.size - offset)
                    bytes.copyInto(buffer, off, offset, offset + count)
                    offset += count
                    return count
                }

                override fun read(): Int {
                    val one = ByteArray(1)
                    val count = read(one, 0, 1)
                    return if (count == -1) -1 else one[0].toInt() and 0xff
                }

                override fun close() {
                    isClosed = true
                    closed.incrementAndGet()
                    released.countDown()
                }
            }
            return WebDavStreamResponse(
                stream = stream,
                statusCode = 206,
                contentLength = bytes.size.toLong(),
                contentRange = ContentRange(start, end, bytes.size.toLong()),
                contentType = "video/mp4",
                totalSize = bytes.size.toLong(),
                close = stream::close,
            )
        }

        override suspend fun download(path: String, target: File, onBytesRead: (Long) -> Unit): Long =
            error("not used")

        fun release() {
            released.countDown()
        }
    }

    private class PreResponseBlockingClient : WebDavClient {
        val cancelHookRegistered = CountDownLatch(1)
        val cancelled = CountDownLatch(1)
        private val released = CountDownLatch(1)

        override suspend fun list(path: String): List<WebDavItem> = emptyList()

        override suspend fun head(path: String): RemoteFileInfo =
            RemoteFileInfo(path, 16L, etag = null, lastModified = null, supportsRange = true)

        override suspend fun readRange(path: String, start: Long, endInclusive: Long): ByteArray =
            error("not used")

        override suspend fun openRangeStream(
            path: String,
            start: Long,
            endInclusive: Long?,
            registerCancellation: (Closeable) -> Unit,
        ): WebDavStreamResponse {
            registerCancellation(
                Closeable {
                    cancelled.countDown()
                    released.countDown()
                },
            )
            cancelHookRegistered.countDown()
            released.await(2, TimeUnit.SECONDS)
            throw IOException("blocked request was cancelled")
        }

        override suspend fun openRangeStream(path: String, start: Long, endInclusive: Long?): WebDavStreamResponse =
            error("cancellable range API should be used")

        override suspend fun download(path: String, target: File, onBytesRead: (Long) -> Unit): Long =
            error("not used")

        fun release() {
            released.countDown()
        }
    }
}
