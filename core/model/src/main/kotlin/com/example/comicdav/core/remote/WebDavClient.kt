package com.example.comicdav.core.remote

import java.io.Closeable
import java.io.File
import java.io.InputStream

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
)
