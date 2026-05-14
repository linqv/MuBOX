package com.example.comicdav.network

import com.example.comicdav.feature.reader.ReaderDiagnosticLog
import com.example.comicdav.nativebridge.RangeProvider
import kotlinx.coroutines.runBlocking

class WebDavRangeProvider(
    private val client: WebDavClient,
    private val path: String,
    private val size: Long,
    private val readAheadBytes: Long = DEFAULT_READ_AHEAD_BYTES,
    private val maxCacheBytes: Long = DEFAULT_MAX_CACHE_BYTES,
    private val logDiagnostic: (String) -> Unit = ReaderDiagnosticLog::event,
) : RangeProvider {
    private val lock = Any()
    private val cache = RangeWindowCache(maxCacheBytes)

    override fun size(fileId: Long): Long = size

    override fun readRange(fileId: Long, start: Long, endInclusive: Long): ByteArray {
        val cached = synchronized(lock) {
            cache.find(start, endInclusive)
        }
        if (cached != null) {
            emitDiagnostic(
                "range_cache_hit path=$path start=$start end=$endInclusive " +
                    "windowStart=${cached.windowStart} windowEnd=${cached.windowEndInclusive} " +
                    "bytes=${cached.bytes.size}",
            )
            return cached.bytes
        }

        val expandedEnd = (endInclusive + readAheadBytes)
            .coerceAtMost(size - 1)
            .coerceAtLeast(endInclusive)
        emitDiagnostic(
            "range_cache_miss path=$path start=$start end=$endInclusive expandedEnd=$expandedEnd",
        )
        val bytes = runBlocking {
            client.readRange(path, start, expandedEnd)
        }
        val postFetch = synchronized(lock) {
            val storeResult = cache.store(start, expandedEnd, bytes)
            val result = cache.find(start, endInclusive)?.bytes
                ?: bytes.copyOfRange(0, (endInclusive - start + 1).toInt())
            PostFetchResult(
                bytes = result,
                storeResult = storeResult,
                cacheBytes = cache.totalBytes(),
                windowCount = cache.windowCount(),
            )
        }
        emitDiagnostic(
            "range_cache_store path=$path start=$start end=$expandedEnd bytes=${bytes.size} " +
                "stored=${postFetch.storeResult.stored} reason=${postFetch.storeResult.skippedReason ?: "none"} " +
                "windows=${postFetch.windowCount} cacheBytes=${postFetch.cacheBytes}",
        )
        postFetch.storeResult.evicted.forEach { evicted ->
            emitDiagnostic(
                "range_cache_evict path=$path start=${evicted.start} end=${evicted.endInclusive} " +
                    "bytes=${evicted.bytes} windows=${postFetch.windowCount} cacheBytes=${postFetch.cacheBytes}",
            )
        }
        return postFetch.bytes
    }

    override fun prefetchRange(start: Long, endInclusive: Long): Boolean {
        if (start >= size) {
            emitDiagnostic("range_prefetch_skip path=$path start=$start end=$endInclusive reason=past_eof")
            return false
        }
        val clampedEnd = endInclusive.coerceAtMost(size - 1)
        val cached = synchronized(lock) {
            cache.find(start, clampedEnd)
        }
        if (cached != null) {
            emitDiagnostic(
                "range_prefetch_hit path=$path start=$start end=$clampedEnd " +
                    "windowStart=${cached.windowStart} windowEnd=${cached.windowEndInclusive}",
            )
            return true
        }

        emitDiagnostic("range_prefetch_start path=$path start=$start end=$clampedEnd")
        val bytes = runBlocking {
            client.readRange(path, start, clampedEnd)
        }
        val postFetch = synchronized(lock) {
            val storeResult = cache.store(start, clampedEnd, bytes)
            PostFetchResult(
                bytes = bytes,
                storeResult = storeResult,
                cacheBytes = cache.totalBytes(),
                windowCount = cache.windowCount(),
            )
        }
        emitDiagnostic(
            "range_prefetch_store path=$path start=$start end=$clampedEnd bytes=${bytes.size} " +
                "stored=${postFetch.storeResult.stored} reason=${postFetch.storeResult.skippedReason ?: "none"} " +
                "windows=${postFetch.windowCount} cacheBytes=${postFetch.cacheBytes}",
        )
        postFetch.storeResult.evicted.forEach { evicted ->
            emitDiagnostic(
                "range_cache_evict path=$path start=${evicted.start} end=${evicted.endInclusive} " +
                    "bytes=${evicted.bytes} windows=${postFetch.windowCount} cacheBytes=${postFetch.cacheBytes}",
            )
        }
        return postFetch.storeResult.stored
    }

    private fun emitDiagnostic(event: String) {
        runCatching {
            logDiagnostic(event)
        }
    }

    internal class RangeWindowCache(private val maxBytes: Long) {
        private val windows = mutableListOf<Window>()
        private var sequence = 0L

        fun find(start: Long, endInclusive: Long): LookupResult? {
            val window = windows.firstOrNull { it.covers(start, endInclusive) } ?: return null
            window.lastAccess = ++sequence
            return LookupResult(
                bytes = window.slice(start, endInclusive),
                windowStart = window.start,
                windowEndInclusive = window.endInclusive,
            )
        }

        fun store(start: Long, endInclusive: Long, bytes: ByteArray): StoreResult {
            if (bytes.size.toLong() > maxBytes) {
                return StoreResult(stored = false, skippedReason = "oversized")
            }
            windows.add(Window(start, endInclusive, bytes, ++sequence))
            return StoreResult(stored = true, evicted = evict())
        }

        fun windowCount(): Int = windows.size

        fun totalBytes(): Long = windows.sumOf { it.bytes.size.toLong() }

        private fun evict(): List<WindowSnapshot> {
            val evicted = mutableListOf<WindowSnapshot>()
            while (totalBytes() > maxBytes) {
                val window = windows.minByOrNull { it.lastAccess } ?: break
                windows.remove(window)
                evicted += window.snapshot()
            }
            return evicted
        }

        internal data class LookupResult(
            val bytes: ByteArray,
            val windowStart: Long,
            val windowEndInclusive: Long,
        )

        internal data class StoreResult(
            val stored: Boolean,
            val skippedReason: String? = null,
            val evicted: List<WindowSnapshot> = emptyList(),
        )

        internal data class WindowSnapshot(
            val start: Long,
            val endInclusive: Long,
            val bytes: Int,
        )

        internal class Window(
            val start: Long,
            val endInclusive: Long,
            val bytes: ByteArray,
            var lastAccess: Long,
        ) {
            fun covers(reqStart: Long, reqEnd: Long): Boolean =
                reqStart >= start && reqEnd <= endInclusive

            fun slice(reqStart: Long, reqEnd: Long): ByteArray {
                val from = (reqStart - start).toInt()
                val toInclusive = (reqEnd - start).toInt()
                return bytes.sliceArray(from..toInclusive)
            }

            fun snapshot(): WindowSnapshot =
                WindowSnapshot(start = start, endInclusive = endInclusive, bytes = bytes.size)
        }
    }

    private data class PostFetchResult(
        val bytes: ByteArray,
        val storeResult: RangeWindowCache.StoreResult,
        val cacheBytes: Long,
        val windowCount: Int,
    )

    private companion object {
        const val DEFAULT_READ_AHEAD_BYTES = 4L * 1024L * 1024L
        const val DEFAULT_MAX_CACHE_BYTES = 64L * 1024L * 1024L
    }
}
