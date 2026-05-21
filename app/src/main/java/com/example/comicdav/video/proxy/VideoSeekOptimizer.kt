package com.example.comicdav.video.proxy

import com.example.comicdav.network.ContentRange
import com.example.comicdav.network.WebDavClient
import com.example.comicdav.network.WebDavStreamResponse
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.max
import kotlin.math.min
import kotlin.coroutines.coroutineContext
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
) : Closeable {
    init {
        require(segmentBytes > 0L) { "segmentBytes must be positive" }
    }

    private val closed = AtomicBoolean(false)
    private val inFlight = ConcurrentHashMap<SegmentKey, InFlightSegment>()
    private val activeResponses = ConcurrentHashMap<SegmentKey, MutableSet<Closeable>>()
    private val prefetchLock = Any()
    private val prefetchJobs = mutableMapOf<String, MutableSet<Job>>()

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
        val output = ByteArrayOutputStream(requestedBytes)

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
                output.write(cachedSlice)
            } else {
                val segment = getOrFetchSegment(
                    client = client,
                    request = request,
                    totalSize = totalSize,
                    segmentIndex = segmentIndex,
                    diagnostics = diagnostics,
                )
                output.write(segment.slice(sliceStart, sliceEnd))
            }
        }

        schedulePrefetch(
            client = client,
            request = request,
            totalSize = totalSize,
            highestSegmentIndex = lastSegmentIndex,
            settings = settings,
            diagnostics = diagnostics,
        )

        val bytes = output.toByteArray()
        return WebDavStreamResponse(
            stream = ByteArrayInputStream(bytes),
            statusCode = 206,
            contentLength = bytes.size.toLong(),
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
        val jobs = synchronized(prefetchLock) {
            prefetchJobs.values.flatten().also { prefetchJobs.clear() }
        }
        jobs.forEach { it.cancel() }
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

        inFlightSegment.waiters.incrementAndGet()
        try {
            return inFlightSegment.deferred.await()
        } finally {
            releaseWaiter(inFlightSegment)
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
    ) {
        val segmentCount = settings.forwardPrefetchMode.segmentCount
        if (segmentCount <= 0 || closed.get()) return

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

            lateinit var job: Job
            job = coroutineScope.launch(Dispatchers.IO, start = CoroutineStart.LAZY) {
                try {
                    getOrFetchSegment(
                        client = client,
                        request = request,
                        totalSize = totalSize,
                        segmentIndex = segmentIndex,
                        diagnostics = diagnostics,
                    )
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    diagnostics.summary {
                        "prefetch_failed stream=${diagnostics.streamId(request.streamId)} " +
                            "error=${error.message ?: error::class.java.simpleName}"
                    }
                    diagnostics.detail {
                        "prefetch_failed stream=${diagnostics.streamId(request.streamId)} " +
                            "segment=$segmentIndex error=${error.message ?: error::class.java.simpleName}"
                    }
                } finally {
                    unregisterPrefetchJob(request.streamId, job)
                }
            }
            registerPrefetchJob(request.streamId, job)
            diagnostics.summary { "prefetch_scheduled stream=${diagnostics.streamId(request.streamId)}" }
            diagnostics.detail { "prefetch_scheduled stream=${diagnostics.streamId(request.streamId)} segment=$segmentIndex" }
            job.start()
        }
    }

    private fun registerPrefetchJob(streamId: String, job: Job) {
        synchronized(prefetchLock) {
            prefetchJobs.getOrPut(streamId) { mutableSetOf() } += job
        }
    }

    private fun unregisterPrefetchJob(streamId: String, job: Job) {
        synchronized(prefetchLock) {
            val jobs = prefetchJobs[streamId] ?: return
            jobs -= job
            if (jobs.isEmpty()) {
                prefetchJobs.remove(streamId)
            }
        }
    }

    private fun cancelPrefetchJobs(streamId: String) {
        val jobs = synchronized(prefetchLock) {
            prefetchJobs.remove(streamId)?.toList().orEmpty()
        }
        jobs.forEach { it.cancel() }
    }

    private fun releaseWaiter(entry: InFlightSegment) {
        entry.waiters.decrementAndGet()
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
        val waiters = AtomicInteger(0)
    }

    private fun Closeable.closeQuietly() {
        runCatching { close() }
    }
}
