package com.example.comicdav.network

import com.example.comicdav.nativebridge.RangeProvider
import kotlinx.coroutines.runBlocking

class WebDavRangeProvider(
    private val client: WebDavClient,
    private val path: String,
    private val size: Long,
    private val readAheadBytes: Long = DEFAULT_READ_AHEAD_BYTES,
) : RangeProvider {
    private val lock = Any()
    private var cachedWindow: CachedWindow? = null

    override fun size(fileId: Long): Long = size

    override fun readRange(fileId: Long, start: Long, endInclusive: Long): ByteArray {
        synchronized(lock) {
            cachedWindow?.slice(start, endInclusive)?.let { return it }
        }

        val mergedEnd = (endInclusive + readAheadBytes)
            .coerceAtMost(size - 1)
            .coerceAtLeast(endInclusive)
        val bytes = runBlocking {
            client.readRange(path, start, mergedEnd)
        }
        val window = CachedWindow(start = start, endInclusive = mergedEnd, bytes = bytes)
        synchronized(lock) {
            cachedWindow = window
            return window.slice(start, endInclusive) ?: bytes
        }
    }

    private data class CachedWindow(
        val start: Long,
        val endInclusive: Long,
        val bytes: ByteArray,
    ) {
        fun slice(requestStart: Long, requestEndInclusive: Long): ByteArray? {
            if (requestStart < start || requestEndInclusive > endInclusive) return null
            val from = (requestStart - start).toInt()
            val toInclusive = (requestEndInclusive - start).toInt()
            return bytes.sliceArray(from..toInclusive)
        }
    }

    private companion object {
        const val DEFAULT_READ_AHEAD_BYTES = 512L * 1024L
    }
}
