package com.example.comicdav.network

import java.io.File

interface WebDavClient {
    suspend fun list(path: String): List<WebDavItem>
    suspend fun head(path: String): RemoteFileInfo
    suspend fun readRange(path: String, start: Long, endInclusive: Long): ByteArray
    suspend fun download(path: String, target: File, onBytesRead: (Long) -> Unit): Long
}
