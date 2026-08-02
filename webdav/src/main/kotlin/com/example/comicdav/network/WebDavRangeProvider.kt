package com.example.comicdav.network

import com.example.comicdav.core.remote.WebDavClient
import com.example.comicdav.core.ports.RangeProvider
import java.io.Closeable
import java.io.IOException
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
        val bytes = try {
            readNetworkRange(fetch)
        } catch (error: Throwable) {
            val cancelled = failInFlight(fetch, error)
            if (cancelled) throw CancellationException("range fetch cancelled")
            throw error
        }
        throwIfCancelled(fetch)
        val result = synchronized(lock) {
            storeReadRange(fetch, start, endInclusive, bytes)
            cache.find(start, endInclusive)?.bytes
                ?: bytes.copyOfRange(0, (endInclusive - start + 1).toInt())
        }
        completeInFlight(fetch, bytes)
        return result
    }

    override fun isRangeCached(start: Long, endInclusive: Long): Boolean =
        synchronized(lock) {
            cache.isCovered(start, endInclusive)
        }

    override fun readCachedRange(start: Long, endInclusive: Long): ByteArray? {
        val cached = synchronized(lock) {
            cache.find(start, endInclusive)
        }
        return cached?.bytes
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
        if (start >= size) return false
        val clampedEnd = endInclusive.coerceAtMost(size - 1)
        val rememberedProtectedRanges = normalizeProtectedRanges(protectedRanges)
        val cached = synchronized(lock) {
            if (rememberedProtectedRanges.isNotEmpty()) {
                latestProtectedRanges = rememberedProtectedRanges
            }
            cache.find(start, clampedEnd)
        }
        if (cached != null) return true

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

        val bytes = try {
            readNetworkRange(fetch)
        } catch (error: Throwable) {
            val cancelled = failInFlight(fetch, error)
            if (cancelled) throw CancellationException("range prefetch cancelled")
            throw error
        }
        throwIfCancelled(fetch)
        val stored = synchronized(lock) {
            storePrefetchRange(fetch, bytes, priority, rememberedProtectedRanges).stored
        }
        completeInFlight(fetch, bytes)
        return stored
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
    ): RangeWindowCache.StoreResult {
        val protectedResult = cache.store(fetch.start, fetch.endInclusive, bytes, protectedRanges)
        if (
            priority <= LOW_PRIORITY_PREFETCH_PRIORITY &&
            protectedResult.skippedReason == "protected_capacity"
        ) {
            return cache.store(fetch.start, fetch.endInclusive, bytes)
        }
        return protectedResult
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
        val bytes = runBlocking { inFlight.deferred.await() }
        return inFlight.slice(bytes, start, endInclusive)
    }

    private fun readNetworkRange(fetch: RegisteredFetch): ByteArray {
        val bytes = runBlocking {
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
        val expectedBytes = fetch.endInclusive - fetch.start + 1
        if (bytes.size.toLong() != expectedBytes) {
            throw IOException(
                "Invalid range response length for $path: " +
                    "start=${fetch.start} end=${fetch.endInclusive} " +
                    "expected=$expectedBytes actual=${bytes.size}",
            )
        }
        return bytes
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
    ) {
        val hasReadAhead = fetch.endInclusive > requestEndInclusive
        val protectedRanges = if (hasReadAhead) latestProtectedRanges else emptyList()
        val expandedResult = if (protectedRanges.isNotEmpty()) {
            cache.store(fetch.start, fetch.endInclusive, bytes, protectedRanges)
        } else {
            cache.store(fetch.start, fetch.endInclusive, bytes)
        }
        if (!hasReadAhead || expandedResult.stored) {
            return
        }

        val requestedByteCount = (requestEndInclusive - requestStart + 1).toInt()
        val requestedBytes = bytes.copyOfRange(0, requestedByteCount)
        val requestedResult = if (protectedRanges.isNotEmpty()) {
            cache.store(fetch.start, requestEndInclusive, requestedBytes, protectedRanges)
        } else {
            cache.store(fetch.start, requestEndInclusive, requestedBytes)
        }
        if (requestedResult.stored) {
            return
        }

        if (requestedResult.skippedReason == "protected_capacity") {
            val priorityResult = cache.store(fetch.start, requestEndInclusive, requestedBytes)
            if (priorityResult.stored) return
        }
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

    private enum class InFlightOwner {
        Demand,
        Prefetch,
    }

    private sealed class FetchDecision {
        data class Join(val inFlight: InFlightRange) : FetchDecision()
        data class Fetch(val fetch: RegisteredFetch) : FetchDecision()
    }

    private companion object {
        const val DEFAULT_READ_AHEAD_BYTES = 4L * 1024L * 1024L
        const val DEFAULT_MAX_CACHE_BYTES = 64L * 1024L * 1024L
        const val LOW_PRIORITY_PREFETCH_PRIORITY = 2
    }
}
