package com.example.comicdav.network

import com.example.comicdav.feature.reader.ReaderDiagnosticLog
import com.example.comicdav.feature.reader.ReaderLogCategory
import com.example.comicdav.nativebridge.RangeProvider
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking

class WebDavRangeProvider(
    private val client: WebDavClient,
    private val path: String,
    private val size: Long,
    private val readAheadBytes: Long = DEFAULT_READ_AHEAD_BYTES,
    private val maxCacheBytes: Long = DEFAULT_MAX_CACHE_BYTES,
    private val logDiagnostic: (String) -> Unit = { line ->
        ReaderDiagnosticLog.detail(ReaderLogCategory.RANGE_CACHE) { line }
    },
) : RangeProvider {
    private val lock = Any()
    private val cache = RangeWindowCache(maxCacheBytes)
    private val inFlightRanges = mutableListOf<InFlightRange>()
    private var latestProtectedRanges: List<LongRange> = emptyList()

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
            val storeDecision = storeReadRange(fetch, start, endInclusive, bytes)
            val result = cache.find(start, endInclusive)?.bytes
                ?: bytes.copyOfRange(0, (endInclusive - start + 1).toInt())
            PostFetchResult(
                bytes = result,
                storeResult = storeDecision.storeResult,
                storeDiagnostic = storeDecision.diagnostic,
                cacheBytes = cache.totalBytes(),
                windowCount = cache.windowCount(),
            )
        }
        completeInFlight(fetch, bytes)
        val storeDiagnostic = postFetch.storeDiagnostic
        emitDiagnostic(
            "range_cache_store path=$path start=${fetch.start} end=${fetch.endInclusive} bytes=${bytes.size} " +
                "stored=${postFetch.storeResult.stored} reason=${postFetch.storeResult.skippedReason ?: "none"} " +
                "evictionMode=${postFetch.storeResult.evictionMode} " +
                "protectedCount=${storeDiagnostic.protectedStats.count} " +
                "protectedBytes=${storeDiagnostic.protectedStats.bytes} " +
                "readAheadStore=${storeDiagnostic.readAheadStore} " +
                "readAheadReason=${storeDiagnostic.readAheadReason ?: "none"} " +
                "storeStart=${storeDiagnostic.storeStart} storeEnd=${storeDiagnostic.storeEndInclusive} " +
                "storeBytes=${storeDiagnostic.storeBytes} " +
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
        return prefetchRange(start, endInclusive, priority = 0, protectedRanges = emptyList())
    }

    override fun prefetchRange(
        start: Long,
        endInclusive: Long,
        priority: Int,
        protectedRanges: List<LongRange>,
    ): Boolean {
        if (start >= size) {
            emitDiagnostic("range_prefetch_skip path=$path start=$start end=$endInclusive reason=past_eof")
            return false
        }
        val clampedEnd = endInclusive.coerceAtMost(size - 1)
        val rememberedProtectedRanges = normalizeProtectedRanges(protectedRanges)
        val protectedStats = protectedStats(rememberedProtectedRanges)
        val cached = synchronized(lock) {
            if (rememberedProtectedRanges.isNotEmpty()) {
                latestProtectedRanges = rememberedProtectedRanges
            }
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
            val storeDecision = storePrefetchRange(fetch, bytes, priority, rememberedProtectedRanges)
            PostFetchResult(
                bytes = bytes,
                storeResult = storeDecision.storeResult,
                storeDiagnostic = StoreDiagnostic(
                    protectedStats = protectedStats,
                    readAheadStore = READ_AHEAD_STORE_NONE,
                    readAheadReason = null,
                    storeStart = fetch.start,
                    storeEndInclusive = fetch.endInclusive,
                    storeBytes = bytes.size,
                    fallbackReason = storeDecision.fallbackReason,
                ),
                cacheBytes = cache.totalBytes(),
                windowCount = cache.windowCount(),
            )
        }
        completeInFlight(fetch, bytes)
        emitDiagnostic(
            "range_prefetch_store path=$path start=${fetch.start} end=${fetch.endInclusive} bytes=${bytes.size} " +
                "stored=${postFetch.storeResult.stored} reason=${postFetch.storeResult.skippedReason ?: "none"} " +
                "priority=$priority evictionMode=${postFetch.storeResult.evictionMode} " +
                "protectedCount=${protectedStats.count} protectedBytes=${protectedStats.bytes} " +
                "fallbackReason=${postFetch.storeDiagnostic.fallbackReason ?: "none"} " +
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

    private fun storePrefetchRange(
        fetch: RegisteredFetch,
        bytes: ByteArray,
        priority: Int,
        protectedRanges: List<LongRange>,
    ): PrefetchStoreDecision {
        val protectedResult = cache.store(fetch.start, fetch.endInclusive, bytes, protectedRanges)
        if (
            priority <= LOW_PRIORITY_PREFETCH_PRIORITY &&
            protectedResult.skippedReason == "protected_capacity"
        ) {
            return PrefetchStoreDecision(
                storeResult = cache.store(fetch.start, fetch.endInclusive, bytes),
                fallbackReason = protectedResult.skippedReason,
            )
        }
        return PrefetchStoreDecision(storeResult = protectedResult)
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

    private fun storeReadRange(
        fetch: RegisteredFetch,
        requestStart: Long,
        requestEndInclusive: Long,
        bytes: ByteArray,
    ): StoreDecision {
        val hasReadAhead = fetch.endInclusive > requestEndInclusive
        val protectedRanges = if (hasReadAhead) latestProtectedRanges else emptyList()
        val protectedStats = protectedStats(protectedRanges)
        val expandedResult = if (protectedRanges.isNotEmpty()) {
            cache.store(fetch.start, fetch.endInclusive, bytes, protectedRanges)
        } else {
            cache.store(fetch.start, fetch.endInclusive, bytes)
        }
        if (!hasReadAhead || expandedResult.stored) {
            return StoreDecision(
                storeResult = expandedResult,
                diagnostic = StoreDiagnostic(
                    protectedStats = protectedStats,
                    readAheadStore = if (hasReadAhead && expandedResult.stored) {
                        READ_AHEAD_STORE_EXPANDED
                    } else {
                        READ_AHEAD_STORE_NONE
                    },
                    readAheadReason = null,
                    storeStart = fetch.start,
                    storeEndInclusive = fetch.endInclusive,
                    storeBytes = bytes.size,
                ),
            )
        }

        val requestedByteCount = (requestEndInclusive - requestStart + 1).toInt()
        val requestedBytes = bytes.copyOfRange(0, requestedByteCount)
        val requestedResult = if (protectedRanges.isNotEmpty()) {
            cache.store(fetch.start, requestEndInclusive, requestedBytes, protectedRanges)
        } else {
            cache.store(fetch.start, requestEndInclusive, requestedBytes)
        }
        if (requestedResult.stored) {
            return StoreDecision(
                storeResult = requestedResult,
                diagnostic = StoreDiagnostic(
                    protectedStats = protectedStats,
                    readAheadStore = READ_AHEAD_STORE_TRIMMED_TO_REQUEST,
                    readAheadReason = expandedResult.skippedReason,
                    storeStart = fetch.start,
                    storeEndInclusive = requestEndInclusive,
                    storeBytes = requestedBytes.size,
                ),
            )
        }

        if (requestedResult.skippedReason == "protected_capacity") {
            val priorityResult = cache.store(fetch.start, requestEndInclusive, requestedBytes)
            if (priorityResult.stored) {
                return StoreDecision(
                    storeResult = priorityResult,
                    diagnostic = StoreDiagnostic(
                        protectedStats = protectedStats,
                        readAheadStore = READ_AHEAD_STORE_TRIMMED_TO_REQUEST,
                        readAheadReason = requestedResult.skippedReason,
                        storeStart = fetch.start,
                        storeEndInclusive = requestEndInclusive,
                        storeBytes = requestedBytes.size,
                    ),
                )
            }
        }

        return StoreDecision(
            storeResult = requestedResult,
            diagnostic = StoreDiagnostic(
                protectedStats = protectedStats,
                readAheadStore = READ_AHEAD_STORE_SKIPPED,
                readAheadReason = requestedResult.skippedReason ?: expandedResult.skippedReason,
                storeStart = fetch.start,
                storeEndInclusive = requestEndInclusive,
                storeBytes = requestedBytes.size,
            ),
        )
    }

    private fun normalizeProtectedRanges(ranges: List<LongRange>): List<LongRange> {
        val sortedRanges = ranges
            .filterNot { it.isEmpty() }
            .sortedBy { it.first }
        if (sortedRanges.isEmpty()) {
            return emptyList()
        }

        val merged = mutableListOf<LongRange>()
        var currentStart = sortedRanges.first().first
        var currentEnd = sortedRanges.first().last
        sortedRanges.drop(1).forEach { range ->
            val adjacent = currentEnd != Long.MAX_VALUE && range.first == currentEnd + 1
            if (range.first <= currentEnd || adjacent) {
                currentEnd = maxOf(currentEnd, range.last)
            } else {
                merged += currentStart..currentEnd
                currentStart = range.first
                currentEnd = range.last
            }
        }
        merged += currentStart..currentEnd
        return merged
    }

    private fun protectedStats(ranges: List<LongRange>): ProtectedStats =
        ProtectedStats(
            count = ranges.size,
            bytes = ranges.sumOf { range -> range.last - range.first + 1 },
        )

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

        fun store(
            start: Long,
            endInclusive: Long,
            bytes: ByteArray,
            protectedRanges: List<LongRange> = emptyList(),
        ): StoreResult {
            if (bytes.size.toLong() > maxBytes) {
                return StoreResult(stored = false, skippedReason = "oversized", evictionMode = "none")
            }
            if (protectedRanges.isNotEmpty()) {
                return storeWithoutEvictingProtected(start, endInclusive, bytes, protectedRanges)
            }
            windows.add(Window(start, endInclusive, bytes, ++sequence))
            return StoreResult(stored = true, evicted = evict(), evictionMode = "lru")
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

        private fun storeWithoutEvictingProtected(
            start: Long,
            endInclusive: Long,
            bytes: ByteArray,
            protectedRanges: List<LongRange>,
        ): StoreResult {
            var projectedBytes = totalBytes() + bytes.size.toLong()
            val windowsToEvict = mutableListOf<Window>()
            val candidates = windows
                .filterNot { it.intersectsAny(protectedRanges) }
                .sortedBy { it.lastAccess }
            for (window in candidates) {
                if (projectedBytes <= maxBytes) {
                    break
                }
                projectedBytes -= window.bytes.size.toLong()
                windowsToEvict += window
            }
            if (projectedBytes > maxBytes) {
                return StoreResult(
                    stored = false,
                    skippedReason = "protected_capacity",
                    evictionMode = "protected",
                )
            }
            val evicted = windowsToEvict.map { it.snapshot() }
            windows.removeAll(windowsToEvict.toSet())
            windows.add(Window(start, endInclusive, bytes, ++sequence))
            return StoreResult(stored = true, evicted = evicted, evictionMode = "protected")
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
            val evictionMode: String = "lru",
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

            fun intersectsAny(ranges: List<LongRange>): Boolean =
                ranges.any { range ->
                    !range.isEmpty() && start <= range.last && endInclusive >= range.first
                }

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
        val storeDiagnostic: StoreDiagnostic,
        val cacheBytes: Long,
        val windowCount: Int,
    )

    private data class StoreDecision(
        val storeResult: RangeWindowCache.StoreResult,
        val diagnostic: StoreDiagnostic,
    )

    private data class PrefetchStoreDecision(
        val storeResult: RangeWindowCache.StoreResult,
        val fallbackReason: String? = null,
    )

    private data class StoreDiagnostic(
        val protectedStats: ProtectedStats,
        val readAheadStore: String,
        val readAheadReason: String?,
        val storeStart: Long,
        val storeEndInclusive: Long,
        val storeBytes: Int,
        val fallbackReason: String? = null,
    )

    private data class ProtectedStats(
        val count: Int,
        val bytes: Long,
    )

    private companion object {
        const val DEFAULT_READ_AHEAD_BYTES = 4L * 1024L * 1024L
        const val DEFAULT_MAX_CACHE_BYTES = 64L * 1024L * 1024L
        const val LOW_PRIORITY_PREFETCH_PRIORITY = 2
        const val READ_AHEAD_STORE_NONE = "none"
        const val READ_AHEAD_STORE_EXPANDED = "expanded"
        const val READ_AHEAD_STORE_TRIMMED_TO_REQUEST = "trimmed_to_request"
        const val READ_AHEAD_STORE_SKIPPED = "skipped"
    }
}
