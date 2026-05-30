package com.example.comicdav.network

import com.example.comicdav.feature.reader.ReaderDiagnosticLog
import com.example.comicdav.feature.reader.ReaderLogCategory
import com.example.comicdav.nativebridge.RangeProvider
import java.io.Closeable
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking

// WebDavRangeProvider implements the RangeProvider interface called synchronously from Rust
// (via JNI) during comic parsing. Inside readRange(), Java uses runBlocking to bridge the
// suspend OkHttp call back to the blocking Rust caller. Callers of ComicEngine.openRemote
// and ComicReaderSession methods must therefore be on a worker thread (annotated @WorkerThread).
class WebDavRangeProvider(
    private val client: WebDavClient,
    private val path: String,
    private val size: Long,
    private val readAheadBytes: Long = DEFAULT_READ_AHEAD_BYTES,
    private val maxCacheBytes: Long = DEFAULT_MAX_CACHE_BYTES,
    private val logDiagnostic: (() -> String) -> Unit = { event ->
        ReaderDiagnosticLog.detail(ReaderLogCategory.RANGE_CACHE, event)
    },
) : RangeProvider {
    private val lock = Any()
    private val cache = RangeWindowCache(maxCacheBytes)
    private val inFlightRanges = mutableListOf<InFlightRange>()
    private val fetchCancellations = mutableMapOf<CompletableDeferred<ByteArray>, Closeable>()
    private val cancelledFetches = mutableSetOf<CompletableDeferred<ByteArray>>()
    private var latestProtectedRanges: List<LongRange> = emptyList()
    private var closed = false

    override fun size(fileId: Long): Long = size

    override fun readRange(fileId: Long, start: Long, endInclusive: Long): ByteArray {
        val cached = synchronized(lock) {
            cache.find(start, endInclusive)
        }
        if (cached != null) {
            emitDiagnostic {
                "range_cache_hit path=$path start=$start end=$endInclusive " +
                    "windowStart=${cached.windowStart} windowEnd=${cached.windowEndInclusive} " +
                    "bytes=${cached.bytes.size}"
            }
            return cached.bytes
        }

        val expandedEnd = (endInclusive + readAheadBytes)
            .coerceAtMost(size - 1)
            .coerceAtLeast(endInclusive)
        val decision = synchronized(lock) {
            checkOpenLocked()
            coveringInFlight(start, endInclusive)?.let { existing ->
                FetchDecision.Join(existing)
            } ?: coveringInFlight(start, expandedEnd)?.let { existing ->
                FetchDecision.Join(existing)
            } ?: FetchDecision.Fetch(registerInFlight(start, expandedEnd, InFlightOwner.Demand))
        }
        if (decision is FetchDecision.Join) {
            return try {
                awaitInFlight(decision.inFlight, start, endInclusive)
            } catch (error: Throwable) {
                if (decision.inFlight.owner != InFlightOwner.Prefetch || isClosed()) {
                    throw error
                }
                emitDiagnostic {
                    "range_inflight_join_fallback path=$path start=$start end=$endInclusive " +
                        "windowStart=${decision.inFlight.start} windowEnd=${decision.inFlight.endInclusive} " +
                        "owner=${decision.inFlight.owner.logValue} error=${error::class.simpleName}"
                }
                fetchReadRange(start, endInclusive, expandedEnd)
            }
        }
        return fetchReadRange(start, endInclusive, expandedEnd, (decision as FetchDecision.Fetch).fetch)
    }

    private fun fetchReadRange(
        start: Long,
        endInclusive: Long,
        expandedEnd: Long,
        registeredFetch: RegisteredFetch? = null,
    ): ByteArray {
        val fetch = registeredFetch ?: synchronized(lock) {
            checkOpenLocked()
            FetchDecision.Fetch(registerInFlight(start, expandedEnd, InFlightOwner.Demand)).fetch
        }
        emitDiagnostic { "range_cache_miss path=$path start=$start end=$endInclusive expandedEnd=$expandedEnd" }
        val bytes = try {
            readNetworkRange(fetch)
        } catch (error: Throwable) {
            val cancelled = failInFlight(fetch, error)
            if (cancelled) throw CancellationException("range fetch cancelled")
            throw error
        }
        throwIfCancelled(fetch)
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
        emitDiagnostic {
            "range_cache_store path=$path start=${fetch.start} end=${fetch.endInclusive} bytes=${bytes.size} " +
                "stored=${postFetch.storeResult.stored} reason=${postFetch.storeResult.skippedReason ?: "none"} " +
                "evictionMode=${postFetch.storeResult.evictionMode} " +
                "protectedCount=${storeDiagnostic.protectedStats.count} " +
                "protectedBytes=${storeDiagnostic.protectedStats.bytes} " +
                "readAheadStore=${storeDiagnostic.readAheadStore} " +
                "readAheadReason=${storeDiagnostic.readAheadReason ?: "none"} " +
                "storeStart=${storeDiagnostic.storeStart} storeEnd=${storeDiagnostic.storeEndInclusive} " +
                "storeBytes=${storeDiagnostic.storeBytes} " +
                "windows=${postFetch.windowCount} cacheBytes=${postFetch.cacheBytes}"
        }
        postFetch.storeResult.evicted.forEach { evicted ->
            emitDiagnostic {
                "range_cache_evict path=$path start=${evicted.start} end=${evicted.endInclusive} " +
                    "bytes=${evicted.bytes} windows=${postFetch.windowCount} cacheBytes=${postFetch.cacheBytes}"
            }
        }
        return postFetch.bytes
    }

    override fun isRangeCached(start: Long, endInclusive: Long): Boolean =
        synchronized(lock) {
            cache.isCovered(start, endInclusive)
        }

    override fun readCachedRange(start: Long, endInclusive: Long): ByteArray? {
        val cached = synchronized(lock) {
            cache.find(start, endInclusive)
        }
        if (cached == null) {
            emitDiagnostic { "range_cache_only_miss path=$path start=$start end=$endInclusive" }
            return null
        }
        emitDiagnostic {
            "range_cache_only_hit path=$path start=$start end=$endInclusive " +
                "windowStart=${cached.windowStart} windowEnd=${cached.windowEndInclusive} " +
                "bytes=${cached.bytes.size}"
        }
        return cached.bytes
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
            emitDiagnostic { "range_prefetch_skip path=$path start=$start end=$endInclusive reason=past_eof" }
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
            emitDiagnostic {
                "range_prefetch_hit path=$path start=$start end=$clampedEnd " +
                    "windowStart=${cached.windowStart} windowEnd=${cached.windowEndInclusive}"
            }
            return true
        }

        val decision = synchronized(lock) {
            checkOpenLocked()
            coveringInFlight(start, clampedEnd)?.let { existing ->
                FetchDecision.Join(existing)
            } ?: FetchDecision.Fetch(registerInFlight(start, clampedEnd, InFlightOwner.Prefetch))
        }
        if (decision is FetchDecision.Join) {
            awaitInFlight(decision.inFlight, start, clampedEnd)
            return synchronized(lock) {
                cache.find(start, clampedEnd) != null
            }
        }
        val fetch = (decision as FetchDecision.Fetch).fetch

        emitDiagnostic { "range_prefetch_start path=$path start=${fetch.start} end=${fetch.endInclusive}" }
        val bytes = try {
            readNetworkRange(fetch)
        } catch (error: Throwable) {
            val cancelled = failInFlight(fetch, error)
            if (cancelled) throw CancellationException("range prefetch cancelled")
            throw error
        }
        throwIfCancelled(fetch)
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
        emitDiagnostic {
            "range_prefetch_store path=$path start=${fetch.start} end=${fetch.endInclusive} bytes=${bytes.size} " +
                "stored=${postFetch.storeResult.stored} reason=${postFetch.storeResult.skippedReason ?: "none"} " +
                "priority=$priority evictionMode=${postFetch.storeResult.evictionMode} " +
                "protectedCount=${protectedStats.count} protectedBytes=${protectedStats.bytes} " +
                "fallbackReason=${postFetch.storeDiagnostic.fallbackReason ?: "none"} " +
                "windows=${postFetch.windowCount} cacheBytes=${postFetch.cacheBytes}"
        }
        postFetch.storeResult.evicted.forEach { evicted ->
            emitDiagnostic {
                "range_cache_evict path=$path start=${evicted.start} end=${evicted.endInclusive} " +
                    "bytes=${evicted.bytes} windows=${postFetch.windowCount} cacheBytes=${postFetch.cacheBytes}"
            }
        }
        return postFetch.storeResult.stored
    }

    override fun cancelPrefetches() {
        cancelInFlightRanges(owner = null, closeProvider = false)
    }

    override fun close() {
        cancelInFlightRanges(owner = null, closeProvider = true)
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

    private fun registerInFlight(
        start: Long,
        endInclusive: Long,
        owner: InFlightOwner,
    ): RegisteredFetch {
        val deferred = CompletableDeferred<ByteArray>()
        inFlightRanges += InFlightRange(
            start = start,
            endInclusive = endInclusive,
            owner = owner,
            deferred = deferred,
        )
        return RegisteredFetch(start = start, endInclusive = endInclusive, deferred = deferred)
    }

    private fun completeInFlight(fetch: RegisteredFetch, bytes: ByteArray) {
        synchronized(lock) {
            inFlightRanges.removeAll { it.deferred === fetch.deferred }
            fetchCancellations.remove(fetch.deferred)
            cancelledFetches.remove(fetch.deferred)
        }
        fetch.deferred.complete(bytes)
    }

    private fun failInFlight(fetch: RegisteredFetch, error: Throwable): Boolean {
        val cancelled = synchronized(lock) {
            inFlightRanges.removeAll { it.deferred === fetch.deferred }
            fetchCancellations.remove(fetch.deferred)
            cancelledFetches.remove(fetch.deferred)
        }
        fetch.deferred.completeExceptionally(
            if (cancelled) {
                CancellationException("range fetch cancelled")
            } else {
                error
            },
        )
        return cancelled
    }

    private fun awaitInFlight(inFlight: InFlightRange, start: Long, endInclusive: Long): ByteArray {
        emitDiagnostic {
            "range_inflight_join path=$path start=$start end=$endInclusive " +
                "windowStart=${inFlight.start} windowEnd=${inFlight.endInclusive} owner=${inFlight.owner.logValue}"
        }
        val bytes = runBlocking { inFlight.deferred.await() }
        return inFlight.slice(bytes, start, endInclusive)
    }

    private fun readNetworkRange(fetch: RegisteredFetch): ByteArray =
        runBlocking {
            try {
                val response = client.openRangeStream(
                    path = path,
                    start = fetch.start,
                    endInclusive = fetch.endInclusive,
                    registerCancellation = { closeable ->
                        registerCancellation(fetch, closeable)
                    },
                )
                try {
                    response.stream.readBytes()
                } finally {
                    response.close()
                }
            } catch (error: UnsupportedOperationException) {
                client.readRange(path, fetch.start, fetch.endInclusive)
            }
        }

    private fun registerCancellation(fetch: RegisteredFetch, closeable: Closeable) {
        val closeImmediately = synchronized(lock) {
            if (inFlightRanges.any { it.deferred === fetch.deferred }) {
                fetchCancellations[fetch.deferred] = closeable
                false
            } else {
                true
            }
        }
        if (closeImmediately) {
            closeable.close()
        }
    }

    private fun cancelInFlightRanges(owner: InFlightOwner?, closeProvider: Boolean) {
        val cancellations: List<Closeable>
        val deferreds: List<CompletableDeferred<ByteArray>>
        synchronized(lock) {
            if (closeProvider) {
                closed = true
            }
            val cancelledRanges = inFlightRanges
                .filter { range -> owner == null || range.owner == owner }
            if (cancelledRanges.isEmpty()) return
            val cancelledDeferreds = cancelledRanges.map { it.deferred }.toSet()
            cancelledFetches += cancelledDeferreds
            inFlightRanges.removeAll { it.deferred in cancelledDeferreds }
            cancellations = cancelledRanges.mapNotNull { fetchCancellations.remove(it.deferred) }
            deferreds = cancelledRanges.map { it.deferred }
        }
        cancellations.forEach { closeable ->
            runCatching { closeable.close() }
        }
        val error = CancellationException("range fetch cancelled")
        deferreds.forEach { deferred ->
            deferred.completeExceptionally(error)
        }
    }

    private fun throwIfCancelled(fetch: RegisteredFetch) {
        if (synchronized(lock) { cancelledFetches.contains(fetch.deferred) }) {
            failInFlight(fetch, CancellationException("range fetch cancelled"))
            throw CancellationException("range fetch cancelled")
        }
    }

    private fun isClosed(): Boolean = synchronized(lock) { closed }

    private fun checkOpenLocked() {
        if (closed) throw CancellationException("range provider closed")
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

    private fun emitDiagnostic(event: () -> String) {
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

        fun isCovered(start: Long, endInclusive: Long): Boolean =
            windows.any { it.covers(start, endInclusive) }

        fun store(
            start: Long,
            endInclusive: Long,
            bytes: ByteArray,
            protectedRanges: List<LongRange> = emptyList(),
        ): StoreResult {
            if (bytes.size.toLong() > maxBytes) {
                return StoreResult(stored = false, skippedReason = "oversized", evictionMode = "none")
            }
            val mergedCandidate = mergedWindow(start, endInclusive, bytes)
            val merged = if (mergedCandidate.window.bytes.size.toLong() <= maxBytes) {
                mergedCandidate
            } else {
                MergedWindow(
                    window = Window(start, endInclusive, bytes, ++sequence),
                    sources = emptyList(),
                )
            }
            if (protectedRanges.isNotEmpty()) {
                return storeWithoutEvictingProtected(merged, protectedRanges)
            }
            windows.removeAll(merged.sources.toSet())
            windows.add(merged.window)
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
            merged: MergedWindow,
            protectedRanges: List<LongRange>,
        ): StoreResult {
            val mergedSourceSet = merged.sources.toSet()
            var projectedBytes = totalBytes() -
                merged.sources.sumOf { it.bytes.size.toLong() } +
                merged.window.bytes.size.toLong()
            val windowsToEvict = mutableListOf<Window>()
            val candidates = windows
                .filterNot { it in mergedSourceSet }
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
            windows.removeAll(mergedSourceSet + windowsToEvict.toSet())
            windows.add(merged.window)
            return StoreResult(stored = true, evicted = evicted, evictionMode = "protected")
        }

        private fun mergedWindow(start: Long, endInclusive: Long, bytes: ByteArray): MergedWindow {
            val sources = windows
                .filter { it.touches(start, endInclusive) }
                .sortedBy { it.start }
            if (sources.isEmpty()) {
                return MergedWindow(
                    window = Window(start, endInclusive, bytes, ++sequence),
                    sources = emptyList(),
                )
            }

            val mergedStart = minOf(start, sources.minOf { it.start })
            val mergedEnd = maxOf(endInclusive, sources.maxOf { it.endInclusive })
            val mergedBytes = ByteArray((mergedEnd - mergedStart + 1).toInt())
            sources.forEach { source ->
                source.bytes.copyInto(
                    destination = mergedBytes,
                    destinationOffset = (source.start - mergedStart).toInt(),
                )
            }
            bytes.copyInto(
                destination = mergedBytes,
                destinationOffset = (start - mergedStart).toInt(),
            )
            return MergedWindow(
                window = Window(mergedStart, mergedEnd, mergedBytes, ++sequence),
                sources = sources,
            )
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

            fun touches(reqStart: Long, reqEnd: Long): Boolean {
                val reqEndPlusOne = if (reqEnd == Long.MAX_VALUE) Long.MAX_VALUE else reqEnd + 1
                val endPlusOne = if (endInclusive == Long.MAX_VALUE) Long.MAX_VALUE else endInclusive + 1
                return start <= reqEndPlusOne && reqStart <= endPlusOne
            }

            fun slice(reqStart: Long, reqEnd: Long): ByteArray {
                val from = (reqStart - start).toInt()
                val toInclusive = (reqEnd - start).toInt()
                return bytes.sliceArray(from..toInclusive)
            }

            fun snapshot(): WindowSnapshot =
                WindowSnapshot(start = start, endInclusive = endInclusive, bytes = bytes.size)
        }

        private data class MergedWindow(
            val window: Window,
            val sources: List<Window>,
        )
    }

    private data class InFlightRange(
        val start: Long,
        val endInclusive: Long,
        val owner: InFlightOwner,
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

    private enum class InFlightOwner(val logValue: String) {
        Demand("demand"),
        Prefetch("prefetch"),
    }

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
