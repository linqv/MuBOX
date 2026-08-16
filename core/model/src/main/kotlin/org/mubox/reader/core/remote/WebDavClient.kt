package org.mubox.reader.core.remote

import java.io.Closeable
import java.io.File
import java.io.InputStream
import java.nio.ByteBuffer

interface WebDavClient {
    suspend fun list(path: String): List<WebDavItem>
    suspend fun head(path: String): RemoteFileInfo
    suspend fun readRange(path: String, start: Long, endInclusive: Long): ByteArray
    suspend fun openRangeStream(
        path: String,
        start: Long,
        endInclusive: Long? = null,
    ): WebDavStreamResponse {
        throw UnsupportedOperationException("Streaming range reads are not supported by this WebDavClient")
    }

    suspend fun openRangeStream(
        path: String,
        start: Long,
        endInclusive: Long? = null,
        registerCancellation: (Closeable) -> Unit,
    ): WebDavStreamResponse =
        openRangeStream(path = path, start = start, endInclusive = endInclusive)

    suspend fun openFullStream(path: String): WebDavStreamResponse =
        openRangeStream(path = path, start = 0L, endInclusive = null)

    suspend fun openFullStream(
        path: String,
        registerCancellation: (Closeable) -> Unit,
    ): WebDavStreamResponse =
        openFullStream(path = path)

    suspend fun download(path: String, target: File, onBytesRead: (Long) -> Unit): Long
}

fun interface WebDavClientFactory {
    fun create(): WebDavClient
}

/** Reads remote response bytes directly into caller-owned memory. */
fun interface RemoteByteSource {
    fun read(target: ByteBuffer): Int
}

data class ContentRange(
    val start: Long,
    val endInclusive: Long,
    val totalSize: Long,
)

data class WebDavStreamResponse(
    val stream: InputStream,
    val statusCode: Int,
    val contentLength: Long,
    val contentRange: ContentRange?,
    val contentType: String?,
    val totalSize: Long?,
    val close: () -> Unit,
    val byteSource: RemoteByteSource? = null,
) {
    private val fallbackReadBuffer = ByteArray(FALLBACK_READ_BUFFER_BYTES)

    /**
     * Fills caller-owned memory without allocating a response-sized Java array.
     *
     * Production OkHttp responses provide [byteSource] and write through Okio directly. Test and
     * compatibility clients fall back to one response-scoped fixed-size scratch buffer.
     */
    fun readInto(target: ByteBuffer): Int {
        if (!target.hasRemaining()) return 0
        byteSource?.let { return it.read(target) }
        val count = stream.read(
            fallbackReadBuffer,
            0,
            minOf(fallbackReadBuffer.size, target.remaining()),
        )
        if (count > 0) target.put(fallbackReadBuffer, 0, count)
        return count
    }

    private companion object {
        const val FALLBACK_READ_BUFFER_BYTES = 64 * 1024
    }
}
