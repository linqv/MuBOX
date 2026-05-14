package com.example.comicdav.network

import com.example.comicdav.feature.reader.ReaderDiagnosticLog
import com.example.comicdav.nativebridge.RangeProvider
import kotlinx.coroutines.CompletableDeferred
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
    private val inFlightRanges = mutableListOf<InFlightRange>()

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
        val decision = synchronized(lock) {
            coveringInFlight(start, endInclusive)?.let { existing ->
                FetchDecision.Join(existing)
            } ?: coveringInFlight(start, expandedEnd)?.let { existing ->
                FetchDecision.Join(existing)
            } ?: FetchDecision.Fetch(registerInFlight(start, expandedEnd))
        }
        if (decision is FetchDecision.Join) {
            return awaitInFlight(decision.inFlight, start, endInclusive)
        }
        val fetch = (decision as FetchDecision.Fetch).fetch
        emitDiagnostic(
            "range_cache_miss path=$path start=$start end=$endInclusive expandedEnd=$expandedEnd",
        )
        val bytes = try {
            runBlocking {
                client.readRange(path, fetch.start, fetch.endInclusive)
            }
        } catch (error: Throwable) {
            failInFlight(fetch, error)
            throw error
        }
        val postFetch = synchronized(lock) {
            val storeResult = cache.store(fetch.start, fetch.endInclusive, bytes)
            val result = cache.find(start, endInclusive)?.bytes
                ?: bytes.copyOfRange(0, (endInclusive - start + 1).toInt())
            PostFetchResult(
                bytes = result,
                storeResult = storeResult,
                cacheBytes = cache.totalBytes(),
                windowCount = cache.windowCount(),
            )
        }
        completeInFlight(fetch, bytes)
        emitDiagnostic(
            "range_cache_store path=$path start=${fetch.start} end=${fetch.endInclusive} bytes=${bytes.size} " +
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

        val decision = synchronized(lock) {
            coveringInFlight(start, clampedEnd)?.let { existing ->
                FetchDecision.Join(existing)
            } ?: FetchDecision.Fetch(registerInFlight(start, clampedEnd))
        }
        if (decision is FetchDecision.Join) {
            awaitInFlight(decision.inFlight, start, clampedEnd)
            return synchronized(lock) {
                cache.find(start, clampedEnd) != null
            }
        }
        val fetch = (decision as FetchDecision.Fetch).fetch

        emitDiagnostic("range_prefetch_start path=$path start=${fetch.start} end=${fetch.endInclusive}")
        val bytes = try {
            runBlocking {
                client.readRange(path, fetch.start, fetch.endInclusive)
            }
        } catch (error: Throwable) {
            failInFlight(fetch, error)
            throw error
        }
        val postFetch = synchronized(lock) {
            val storeResult = cache.store(fetch.start, fetch.endInclusive, bytes)
            PostFetchResult(
                bytes = bytes,
                storeResult = storeResult,
                cacheBytes = cache.totalBytes(),
                windowCount = cache.windowCount(),
            )
        }
        completeInFlight(fetch, bytes)
        emitDiagnostic(
            "range_prefetch_store path=$path start=${fetch.start} end=${fetch.endInclusive} bytes=${bytes.size} " +
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

    private fun coveringInFlight(start: Long, endInclusive: Long): InFlightRange? =
        inFlightRanges.firstOrNull { it.covers(start, endInclusive) }

    private fun registerInFlight(start: Long, endInclusive: Long): RegisteredFetch {
        val deferred = CompletableDeferred<ByteArray>()
        inFlightRanges += InFlightRange(start = start, endInclusive = endInclusive, deferred = deferred)
        return RegisteredFetch(start = start, endInclusive = endInclusive, deferred = deferred)
    }

    private fun completeInFlight(fetch: RegisteredFetch, bytes: ByteArray) {
        synchronized(lock) {
            inFlightRanges.removeAll { it.deferred === fetch.deferred }
        }
        fetch.deferred.complete(bytes)
    }

    private fun failInFlight(fetch: RegisteredFetch, error: Throwable) {
        synchronized(lock) {
            inFlightRanges.removeAll { it.deferred === fetch.deferred }
        }
        fetch.deferred.completeExceptionally(error)
    }

    private fun awaitInFlight(inFlight: InFlightRange, start: Long, endInclusive: Long): ByteArray {
        emitDiagnostic(
            "range_inflight_join path=$path start=$start end=$endInclusive " +
                "windowStart=${inFlight.start} windowEnd=${inFlight.endInclusive}",
        )
        val bytes = runBlocking { inFlight.deferred.await() }
        return inFlight.slice(bytes, start, endInclusive)
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

    private data class InFlightRange(
        val start: Long,
        val endInclusive: Long,
        val deferred: CompletableDeferred<ByteArray>,
    ) {
        fun covers(reqStart: Long, reqEndInclusive: Long): Boolean =
            reqStart >= start && reqEndInclusive <= endInclusive

        fun slice(bytes: ByteArray, reqStart: Long, reqEndInclusive: Long): ByteArray {
            val from = (reqStart - start).toInt()
            val toExclusive = (reqEndInclusive - start + 1).toInt()
            return bytes.copyOfRange(from, toExclusive)
        }
    }

    private data class RegisteredFetch(
        val start: Long,
        val endInclusive: Long,
        val deferred: CompletableDeferred<ByteArray>,
    )

    private sealed class FetchDecision {
        data class Join(val inFlight: InFlightRange) : FetchDecision()
        data class Fetch(val fetch: RegisteredFetch) : FetchDecision()
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
