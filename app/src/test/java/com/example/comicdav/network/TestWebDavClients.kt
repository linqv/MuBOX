package com.example.comicdav.network

import java.io.ByteArrayInputStream
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.CountDownLatch
import kotlinx.coroutines.CompletableDeferred

internal class RecordingWebDavClient(
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

    override suspend fun download(path: String, target: java.io.File, onBytesRead: (Long) -> Unit): Long =
        error("unused")
}

internal class TruncatedStreamingWebDavClient(
    private val bytes: ByteArray,
) : WebDavClient {
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
        val requested = bytes.sliceArray(start.toInt()..end.toInt())
        val truncated = requested.copyOf(requested.size - 1)
        val stream = ByteArrayInputStream(truncated)
        registerCancellation(stream)
        return WebDavStreamResponse(
            stream = stream,
            statusCode = 206,
            contentLength = requested.size.toLong(),
            contentRange = ContentRange(start, end, bytes.size.toLong()),
            contentType = "application/octet-stream",
            totalSize = bytes.size.toLong(),
            close = stream::close,
        )
    }

    override suspend fun download(path: String, target: java.io.File, onBytesRead: (Long) -> Unit): Long =
        error("unused")
}

internal class BlockingWebDavClient(
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

    override suspend fun download(path: String, target: java.io.File, onBytesRead: (Long) -> Unit): Long =
        error("unused")
}

internal class BlockingFirstRangeWebDavClient(
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

    override suspend fun download(path: String, target: java.io.File, onBytesRead: (Long) -> Unit): Long =
        error("unused")
}

internal class FailingFirstRangeWebDavClient(
    private val bytes: ByteArray,
    private val releaseFirstRead: CompletableDeferred<Unit>,
    private val firstReadStarted: CountDownLatch,
) : WebDavClient {
    val rangeCalls = mutableListOf<Pair<Long, Long>>()
    private var shouldFailNextRead = true

    override suspend fun list(path: String): List<WebDavItem> = emptyList()

    override suspend fun head(path: String): RemoteFileInfo =
        RemoteFileInfo(path, bytes.size.toLong(), etag = null, lastModified = null, supportsRange = true)

    override suspend fun readRange(path: String, start: Long, endInclusive: Long): ByteArray {
        rangeCalls += start to endInclusive
        if (shouldFailNextRead) {
            shouldFailNextRead = false
            firstReadStarted.countDown()
            releaseFirstRead.await()
            throw IOException("prefetch cancelled")
        }
        return bytes.sliceArray(start.toInt()..endInclusive.toInt())
    }

    override suspend fun download(path: String, target: java.io.File, onBytesRead: (Long) -> Unit): Long =
        error("unused")
}

internal class CancellableBlockingWebDavClient(
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
            close = { stream.close() },
        )
    }

    override suspend fun download(path: String, target: java.io.File, onBytesRead: (Long) -> Unit): Long =
        error("unused")
}

internal class BlockingTestInputStream(
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
