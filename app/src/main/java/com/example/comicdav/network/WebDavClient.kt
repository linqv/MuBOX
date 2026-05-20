package com.example.comicdav.network

import java.io.ByteArrayInputStream
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
    ): WebDavStreamResponse =
        WebDavStreamResponse(
            stream = ByteArrayInputStream(readRange(path, start, endInclusive ?: Long.MAX_VALUE)),
            statusCode = if (endInclusive == null) 200 else 206,
            contentLength = -1L,
            contentRange = null,
            contentType = null,
            totalSize = null,
            close = {},
        )
    suspend fun download(path: String, target: File, onBytesRead: (Long) -> Unit): Long
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
