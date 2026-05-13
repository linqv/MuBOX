package com.example.comicdav.network

import com.example.comicdav.nativebridge.RangeProvider
import kotlinx.coroutines.runBlocking

class WebDavRangeProvider(
    private val client: WebDavClient,
    private val path: String,
    private val size: Long,
) : RangeProvider {
    override fun size(fileId: Long): Long = size

    override fun readRange(fileId: Long, start: Long, endInclusive: Long): ByteArray =
        runBlocking {
            client.readRange(path, start, endInclusive)
        }
}
