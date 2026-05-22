package com.example.comicdav.video.proxy

import com.example.comicdav.network.ContentRange
import com.example.comicdav.network.WebDavClient
import com.example.comicdav.network.WebDavStreamResponse
import java.io.ByteArrayInputStream
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.coroutineContext
import kotlin.math.max
import kotlin.math.min
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch

internal class VideoSeekOptimizer(
    private val coroutineScope: CoroutineScope,
    private val cache: VideoRangeMemoryCache = VideoRangeMemoryCache(),
    private val segmentBytes: Long = VideoRangeMemoryCache.DEFAULT_SEGMENT_BYTES,
    private val smallRangeDirectThresholdBytes: Long =
        (segmentBytes / 8L).coerceAtMost(DEFAULT_SMALL_RANGE_DIRECT_THRESHOLD_BYTES),
) : Closeable {
    init {
        require(segmentBytes > 0L) { "segmentBytes must be positive" }
        require(smallRangeDirectThresholdBytes >= 0L) { "smallRangeDirectThresholdBytes must not be negative" }
    }

    private val closed = AtomicBoolean(false)
    private val inFlight = ConcurrentHashMap<SegmentKey, InFlightSegment>()
    private val activeResponses = ConcurrentHashMap<SegmentKey, MutableSet<Closeable>>()
    private val prefetchLock = Any()
    private val prefetchStates = mutableMapOf<String, PrefetchState>()

    suspend fun openRangeStream(
        client: WebDavClient,
        request: VideoStreamRequest,
        totalSize: Long,
        start: Long,
        endInclusive: Long,
        settings: VideoProxySettings,
    ): WebDavStreamResponse {
        ensureOpen()
        require(totalSize >= 0L) { "totalSize must not be negative" }
        require(start >= 0L) { "start must not be negative" }
        require(endInclusive >= start) { "endInclusive must be greater than or equal to start" }
        require(endInclusive < totalSize) { "endInclusive must be inside totalSize" }

        if (!settings.seekOptimizationEnabled) {
            return openDirectRange(client, request, totalSize, start, endInclusive)
        }

        val diagnostics = VideoProxyDiagnostics(settings.diagnosticsMode)
        val firstSegmentIndex = segmentIndexFor(start)
        val lastSegmentIndex = segmentIndexFor(endInclusive)
        val requestedBytes = checkedRequestedByteCount(start, endInclusive)
        val foregroundGeneration = beginForegroundGeneration(
            streamId = request.streamId,
            foregroundSegments = segmentIndexesBetween(firstSegmentIndex, lastSegmentIndex),
        )

        if (shouldStreamSmallRangeDirectly(firstSegmentIndex, lastSegmentIndex, requestedBytes)) {
            val cachedSlice = cache.getSegmentSlice(request.streamId, firstSegmentIndex, start, endInclusive)
            if (cachedSlice == null) {
                diagnostics.summary { "small_range_direct stream=${diagnostics.streamId(request.streamId)}" }
                diagnostics.detail {
                    "small_range_direct stream=${diagnostics.streamId(request.streamId)} " +
                        "range=$start-$endInclusive threshold=$smallRangeDirectThresholdBytes"
                }
                if (!isFullSegmentRange(firstSegmentIndex, totalSize, start, endInclusive)) {
                    scheduleSegmentWarmup(
                        client = client,
                        request = request,
                        totalSize = totalSize,
                        segmentIndex = firstSegmentIndex,
                        diagnostics = diagnostics,
                        generation = foregroundGeneration,
                    )
                }
                return client.openRangeStream(request.remotePath, start, endInclusive)
            }
        }

        val slices = ArrayList<ByteArray>()
        for (segmentIndex in firstSegmentIndex..lastSegmentIndex) {
            val segmentStart = segmentIndex * segmentBytes
            val segmentEnd = (segmentStart + segmentBytes - 1L).coerceAtMost(totalSize - 1L)
            val sliceStart = max(start, segmentStart)
            val sliceEnd = min(endInclusive, segmentEnd)
            val cachedSlice = cache.getSegmentSlice(request.streamId, segmentIndex, sliceStart, sliceEnd)
            if (cachedSlice != null) {
                diagnostics.summary { "cache_hit stream=${diagnostics.streamId(request.streamId)}" }
                diagnostics.detail {
                    "cache_hit stream=${diagnostics.streamId(request.streamId)} " +
                        "segment=$segmentIndex range=$segmentStart-$segmentEnd cache_bytes=${cache.totalBytes()}"
                }
                slices += cachedSlice
            } else {
                val segment = getOrFetchSegment(
                    client = client,
                    request = request,
                    totalSize = totalSize,
                    segmentIndex = segmentIndex,
                    diagnostics = diagnostics,
                    waiterKind = WaiterKind.FOREGROUND,
                )
                slices += segment.slice(sliceStart, sliceEnd)
            }
        }

        schedulePrefetch(
            client = client,
            request = request,
            totalSize = totalSize,
            highestSegmentIndex = lastSegmentIndex,
            settings = settings,
            diagnostics = diagnostics,
            generation = foregroundGeneration,
        )

        return WebDavStreamResponse(
            stream = ByteArraySlicesInputStream(slices),
            statusCode = 206,
            contentLength = requestedBytes.toLong(),
            contentRange = ContentRange(start, endInclusive, totalSize),
            contentType = request.mimeType,
            totalSize = totalSize,
            close = {},
        )
    }

    fun removeStream(streamId: String) {
        cancelPrefetchJobs(streamId)
        inFlight.entries
            .filter { it.key.streamId == streamId }
            .forEach { entry ->
                entry.value.deferred.cancel()
                inFlight.remove(entry.key, entry.value)
            }
        activeResponses.keys
            .filter { it.streamId == streamId }
            .forEach(::closeActiveResponses)
        cache.removeStream(streamId)
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        val states = synchronized(prefetchLock) {
            prefetchStates.values.map { state -> state.copyForCancellation() }.also { prefetchStates.clear() }
        }
        states.forEach(::cancelPrefetchState)
        inFlight.values.forEach { it.deferred.cancel() }
        inFlight.clear()
        activeResponses.keys.toList().forEach(::closeActiveResponses)
        cache.clear()
    }

    private suspend fun openDirectRange(
        client: WebDavClient,
        request: VideoStreamRequest,
        totalSize: Long,
        start: Long,
        endInclusive: Long,
    ): WebDavStreamResponse {
        val response = client.openRangeStream(request.remotePath, start, endInclusive)
        try {
            val bytes = response.stream.readBytes()
            val expectedBytes = endInclusive - start + 1L
            if (bytes.size.toLong() != expectedBytes) {
                throw IOException("Expected $expectedBytes bytes for range $start-$endInclusive, got ${bytes.size}")
            }
            return WebDavStreamResponse(
                stream = ByteArrayInputStream(bytes),
                statusCode = 206,
                contentLength = bytes.size.toLong(),
                contentRange = ContentRange(start, endInclusive, totalSize),
                contentType = request.mimeType,
                totalSize = totalSize,
                close = {},
            )
        } finally {
            response.close()
        }
    }

    private suspend fun getOrFetchSegment(
        client: WebDavClient,
        request: VideoStreamRequest,
        totalSize: Long,
        segmentIndex: Long,
        diagnostics: VideoProxyDiagnostics,
        waiterKind: WaiterKind,
    ): VideoRangeMemoryCache.Segment {
        cache.getSegment(request.streamId, segmentIndex)?.let { segment ->
            diagnostics.summary { "cache_hit stream=${diagnostics.streamId(request.streamId)}" }
            diagnostics.detail {
                "cache_hit stream=${diagnostics.streamId(request.streamId)} " +
                    "segment=$segmentIndex range=${segment.start}-${segment.endInclusive} cache_bytes=${cache.totalBytes()}"
            }
            return segment
        }

        diagnostics.summary { "cache_miss stream=${diagnostics.streamId(request.streamId)}" }
        diagnostics.detail { "cache_miss stream=${diagnostics.streamId(request.streamId)} segment=$segmentIndex" }

        val created = AtomicBoolean(false)
        val key = SegmentKey(request.streamId, segmentIndex)
        val inFlightSegment = inFlight.computeIfAbsent(key) {
            created.set(true)
            val deferred = coroutineScope.async(Dispatchers.IO) {
                fetchSegment(
                    client = client,
                    request = request,
                    totalSize = totalSize,
                    segmentIndex = segmentIndex,
                    diagnostics = diagnostics,
                )
            }
            InFlightSegment(deferred).also { newEntry ->
                deferred.invokeOnCompletion {
                    inFlight.remove(key, newEntry)
                }
            }
        }

        if (!created.get()) {
            diagnostics.summary { "inflight_join stream=${diagnostics.streamId(request.streamId)}" }
            diagnostics.detail { "inflight_join stream=${diagnostics.streamId(request.streamId)} segment=$segmentIndex" }
        }

        inFlightSegment.increment(waiterKind)
        try {
            return inFlightSegment.deferred.await()
        } finally {
            inFlightSegment.decrement(waiterKind)
        }
    }

    private suspend fun fetchSegment(
        client: WebDavClient,
        request: VideoStreamRequest,
        totalSize: Long,
        segmentIndex: Long,
        diagnostics: VideoProxyDiagnostics,
    ): VideoRangeMemoryCache.Segment {
        cache.getSegment(request.streamId, segmentIndex)?.let { segment ->
            diagnostics.summary { "cache_hit stream=${diagnostics.streamId(request.streamId)}" }
            diagnostics.detail {
                "cache_hit stream=${diagnostics.streamId(request.streamId)} " +
                    "segment=$segmentIndex range=${segment.start}-${segment.endInclusive} cache_bytes=${cache.totalBytes()}"
            }
            return segment
        }

        val segmentStart = segmentIndex * segmentBytes
        val segmentEnd = (segmentStart + segmentBytes - 1L).coerceAtMost(totalSize - 1L)
        val expectedBytes = segmentEnd - segmentStart + 1L
        val startedAt = System.nanoTime()
        diagnostics.summary { "remote_fetch stream=${diagnostics.streamId(request.streamId)}" }
        diagnostics.detail {
            "remote_fetch stream=${diagnostics.streamId(request.streamId)} " +
                "segment=$segmentIndex range=$segmentStart-$segmentEnd"
        }

        val key = SegmentKey(request.streamId, segmentIndex)
        var requestCloseable: Closeable? = null
        val response = try {
            client.openRangeStream(request.remotePath, segmentStart, segmentEnd) { closeable ->
                requestCloseable = closeable
                addActiveResponse(key, closeable)
            }
        } finally {
            requestCloseable?.let { removeActiveResponse(key, it) }
        }
        val responseCloseable = Closeable { response.close() }
        addActiveResponse(key, responseCloseable)
        try {
            coroutineContext.ensureActive()
            val bytes = response.stream.readBytes()
            coroutineContext.ensureActive()
            if (bytes.size.toLong() != expectedBytes) {
                throw IOException("Expected $expectedBytes bytes for segment $segmentIndex, got ${bytes.size}")
            }
            val segment = VideoRangeMemoryCache.Segment(
                streamId = request.streamId,
                segmentIndex = segmentIndex,
                start = segmentStart,
                bytes = bytes,
            )
            val stored = cache.putSegment(request.streamId, segmentIndex, segmentStart, bytes)
            val elapsedMillis = (System.nanoTime() - startedAt) / 1_000_000L
            diagnostics.detail {
                "remote_fetch_complete stream=${diagnostics.streamId(request.streamId)} " +
                    "segment=$segmentIndex elapsed_ms=$elapsedMillis stored=$stored cache_bytes=${cache.totalBytes()}"
            }
            return segment
        } finally {
            removeActiveResponse(key, responseCloseable)
            responseCloseable.closeQuietly()
        }
    }

    private fun schedulePrefetch(
        client: WebDavClient,
        request: VideoStreamRequest,
        totalSize: Long,
        highestSegmentIndex: Long,
        settings: VideoProxySettings,
        diagnostics: VideoProxyDiagnostics,
        generation: Long,
    ) {
        val segmentCount = settings.forwardPrefetchMode.segmentCount
        if (segmentCount <= 0 || closed.get()) return

        val segmentIndexes = ArrayList<Long>()
        for (offset in 1..segmentCount) {
            val segmentIndex = highestSegmentIndex + offset
            val segmentStart = segmentIndex * segmentBytes
            if (segmentStart >= totalSize) {
                diagnostics.detail {
                    "prefetch_skipped stream=${diagnostics.streamId(request.streamId)} " +
                        "segment=$segmentIndex reason=end_of_stream"
                }
                continue
            }
            val key = SegmentKey(request.streamId, segmentIndex)
            if (cache.containsSegment(request.streamId, segmentIndex)) {
                diagnostics.summary { "prefetch_skipped stream=${diagnostics.streamId(request.streamId)} reason=cache_hit" }
                diagnostics.detail { "prefetch_skipped stream=${diagnostics.streamId(request.streamId)} segment=$segmentIndex reason=cache_hit" }
                continue
            }
            if (inFlight.containsKey(key)) {
                diagnostics.summary { "prefetch_skipped stream=${diagnostics.streamId(request.streamId)} reason=inflight" }
                diagnostics.detail { "prefetch_skipped stream=${diagnostics.streamId(request.streamId)} segment=$segmentIndex reason=inflight" }
                continue
            }
            segmentIndexes += segmentIndex
        }

        schedulePrefetchSegments(
            client = client,
            request = request,
            totalSize = totalSize,
            segmentIndexes = segmentIndexes,
            diagnostics = diagnostics,
            generation = generation,
        )
    }

    private fun scheduleSegmentWarmup(
        client: WebDavClient,
        request: VideoStreamRequest,
        totalSize: Long,
        segmentIndex: Long,
        diagnostics: VideoProxyDiagnostics,
        generation: Long,
    ) {
        if (closed.get()) return
        val key = SegmentKey(request.streamId, segmentIndex)
        if (cache.containsSegment(request.streamId, segmentIndex) || inFlight.containsKey(key)) return
        schedulePrefetchSegments(
            client = client,
            request = request,
            totalSize = totalSize,
            segmentIndexes = listOf(segmentIndex),
            diagnostics = diagnostics,
            generation = generation,
        )
    }

    private fun schedulePrefetchSegments(
        client: WebDavClient,
        request: VideoStreamRequest,
        totalSize: Long,
        segmentIndexes: List<Long>,
        diagnostics: VideoProxyDiagnostics,
        generation: Long,
    ) {
        if (segmentIndexes.isEmpty() || closed.get()) return

        val keys = segmentIndexes.map { SegmentKey(request.streamId, it) }
        lateinit var job: Job
        job = coroutineScope.launch(Dispatchers.IO, start = CoroutineStart.LAZY) {
            try {
                for (segmentIndex in segmentIndexes) {
                    coroutineContext.ensureActive()
                    if (!isCurrentPrefetchGeneration(request.streamId, generation)) return@launch
                    val key = SegmentKey(request.streamId, segmentIndex)
                    if (cache.containsSegment(request.streamId, segmentIndex)) {
                        diagnostics.summary { "prefetch_skipped stream=${diagnostics.streamId(request.streamId)} reason=cache_hit" }
                        diagnostics.detail {
                            "prefetch_skipped stream=${diagnostics.streamId(request.streamId)} " +
                                "segment=$segmentIndex reason=cache_hit"
                        }
                        continue
                    }
                    if (inFlight.containsKey(key)) {
                        diagnostics.summary { "prefetch_skipped stream=${diagnostics.streamId(request.streamId)} reason=inflight" }
                        diagnostics.detail {
                            "prefetch_skipped stream=${diagnostics.streamId(request.streamId)} " +
                                "segment=$segmentIndex reason=inflight"
                        }
                        continue
                    }
                    getOrFetchSegment(
                        client = client,
                        request = request,
                        totalSize = totalSize,
                        segmentIndex = segmentIndex,
                        diagnostics = diagnostics,
                        waiterKind = WaiterKind.PREFETCH,
                    )
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                if (isCurrentPrefetchGeneration(request.streamId, generation)) {
                    diagnostics.summary {
                        "prefetch_failed stream=${diagnostics.streamId(request.streamId)} " +
                            "error=${error.message ?: error::class.java.simpleName}"
                    }
                    diagnostics.detail {
                        "prefetch_failed stream=${diagnostics.streamId(request.streamId)} " +
                            "error=${error.message ?: error::class.java.simpleName}"
                    }
                }
            } finally {
                unregisterPrefetchJob(request.streamId, generation, keys)
            }
        }

        if (registerPrefetchJob(request.streamId, generation, keys, job)) {
            diagnostics.summary { "prefetch_scheduled stream=${diagnostics.streamId(request.streamId)}" }
            diagnostics.detail {
                "prefetch_scheduled stream=${diagnostics.streamId(request.streamId)} " +
                    "segments=${segmentIndexes.joinToString(",")}"
            }
            job.start()
        } else {
            job.cancel()
        }
    }

    private fun beginForegroundGeneration(streamId: String, foregroundSegments: Set<Long>): Long {
        val cancellation = synchronized(prefetchLock) {
            val state = prefetchStates.getOrPut(streamId) { PrefetchState() }
            state.generation += 1L
            val overlapsForeground = state.keys.any { key -> key.segmentIndex in foregroundSegments }
            if (overlapsForeground) {
                PrefetchCancellation(null, emptyList())
            } else {
                val cancellation = state.copyForCancellation()
                state.job = null
                state.keys.clear()
                cancellation
            }
        }
        cancelPrefetchState(cancellation)
        return synchronized(prefetchLock) { prefetchStates.getValue(streamId).generation }
    }

    private fun registerPrefetchJob(
        streamId: String,
        generation: Long,
        keys: List<SegmentKey>,
        job: Job,
    ): Boolean =
        synchronized(prefetchLock) {
            val state = prefetchStates.getOrPut(streamId) { PrefetchState() }
            if (state.generation != generation) return@synchronized false
            state.job?.cancel()
            state.job = job
            state.keys.clear()
            state.keys += keys
            true
        }

    private fun unregisterPrefetchJob(streamId: String, generation: Long, keys: List<SegmentKey>) {
        synchronized(prefetchLock) {
            val state = prefetchStates[streamId] ?: return
            if (state.generation != generation) return
            state.keys.removeAll(keys.toSet())
            if (state.keys.isEmpty()) state.job = null
        }
    }

    private fun cancelPrefetchJobs(streamId: String) {
        val state = synchronized(prefetchLock) {
            prefetchStates.remove(streamId)?.copyForCancellation()
        }
        state?.let(::cancelPrefetchState)
    }

    private fun isCurrentPrefetchGeneration(streamId: String, generation: Long): Boolean =
        synchronized(prefetchLock) {
            prefetchStates[streamId]?.generation == generation
        }

    private fun cancelPrefetchState(state: PrefetchCancellation) {
        state.job?.cancel()
        state.keys.forEach { key ->
            val entry = inFlight[key]
            if (entry != null && entry.foregroundWaiters.get() <= 0) {
                inFlight.remove(key, entry)
                entry.deferred.cancel()
                closeActiveResponses(key)
            }
        }
    }

    private fun addActiveResponse(key: SegmentKey, closeable: Closeable): Boolean {
        if (closed.get() || !inFlight.containsKey(key)) {
            closeable.closeQuietly()
            return false
        }
        val responses = activeResponses.computeIfAbsent(key) {
            ConcurrentHashMap.newKeySet()
        }
        responses += closeable
        if (closed.get() || !inFlight.containsKey(key)) {
            removeActiveResponse(key, closeable)
            closeable.closeQuietly()
            return false
        }
        return true
    }

    private fun removeActiveResponse(key: SegmentKey, closeable: Closeable) {
        val responses = activeResponses[key] ?: return
        responses -= closeable
        if (responses.isEmpty()) {
            activeResponses.remove(key, responses)
        }
    }

    private fun closeActiveResponses(key: SegmentKey) {
        activeResponses.remove(key)?.forEach { closeable ->
            closeable.closeQuietly()
        }
    }

    private fun segmentIndexFor(byteOffset: Long): Long = byteOffset / segmentBytes

    private fun segmentIndexesBetween(firstSegmentIndex: Long, lastSegmentIndex: Long): Set<Long> =
        buildSet {
            for (segmentIndex in firstSegmentIndex..lastSegmentIndex) {
                add(segmentIndex)
            }
        }

    private fun shouldStreamSmallRangeDirectly(
        firstSegmentIndex: Long,
        lastSegmentIndex: Long,
        requestedBytes: Int,
    ): Boolean =
        smallRangeDirectThresholdBytes > 0L &&
            requestedBytes.toLong() <= smallRangeDirectThresholdBytes &&
            firstSegmentIndex == lastSegmentIndex

    private fun isFullSegmentRange(
        segmentIndex: Long,
        totalSize: Long,
        start: Long,
        endInclusive: Long,
    ): Boolean {
        val segmentStart = segmentIndex * segmentBytes
        val segmentEnd = (segmentStart + segmentBytes - 1L).coerceAtMost(totalSize - 1L)
        return start == segmentStart && endInclusive == segmentEnd
    }

    private fun checkedRequestedByteCount(start: Long, endInclusive: Long): Int {
        val count = endInclusive - start + 1L
        if (count > Int.MAX_VALUE) {
            throw IOException("Requested range is too large to buffer: $count bytes")
        }
        return count.toInt()
    }

    private fun ensureOpen() {
        if (closed.get()) {
            throw IOException("Video seek optimizer is closed")
        }
    }

    private data class SegmentKey(
        val streamId: String,
        val segmentIndex: Long,
    )

    private class InFlightSegment(
        val deferred: Deferred<VideoRangeMemoryCache.Segment>,
    ) {
        val foregroundWaiters = AtomicInteger(0)
        private val prefetchWaiters = AtomicInteger(0)

        fun increment(kind: WaiterKind) {
            when (kind) {
                WaiterKind.FOREGROUND -> foregroundWaiters.incrementAndGet()
                WaiterKind.PREFETCH -> prefetchWaiters.incrementAndGet()
            }
        }

        fun decrement(kind: WaiterKind) {
            when (kind) {
                WaiterKind.FOREGROUND -> foregroundWaiters.decrementAndGet()
                WaiterKind.PREFETCH -> prefetchWaiters.decrementAndGet()
            }
        }
    }

    private enum class WaiterKind {
        FOREGROUND,
        PREFETCH,
    }

    private class PrefetchState {
        var generation: Long = 0L
        var job: Job? = null
        val keys: MutableSet<SegmentKey> = mutableSetOf()

        fun copyForCancellation(): PrefetchCancellation =
            PrefetchCancellation(job, keys.toList())
    }

    private data class PrefetchCancellation(
        val job: Job?,
        val keys: List<SegmentKey>,
    )

    private class ByteArraySlicesInputStream(
        slices: List<ByteArray>,
    ) : InputStream() {
        private val slices = slices.filter { it.isNotEmpty() }
        private var sliceIndex = 0
        private var offset = 0

        override fun read(): Int {
            while (sliceIndex < slices.size) {
                val slice = slices[sliceIndex]
                if (offset < slice.size) {
                    return slice[offset++].toInt() and 0xff
                }
                sliceIndex += 1
                offset = 0
            }
            return -1
        }

        override fun read(buffer: ByteArray, off: Int, len: Int): Int {
            if (len == 0) return 0
            var total = 0
            var outputOffset = off
            var remaining = len
            while (remaining > 0 && sliceIndex < slices.size) {
                val slice = slices[sliceIndex]
                if (offset >= slice.size) {
                    sliceIndex += 1
                    offset = 0
                    continue
                }
                val count = minOf(remaining, slice.size - offset)
                slice.copyInto(buffer, outputOffset, offset, offset + count)
                offset += count
                outputOffset += count
                remaining -= count
                total += count
            }
            return if (total == 0) -1 else total
        }
    }

    private fun Closeable.closeQuietly() {
        runCatching { close() }
    }

    private companion object {
        const val DEFAULT_SMALL_RANGE_DIRECT_THRESHOLD_BYTES: Long = 256L * 1024L
    }
}
