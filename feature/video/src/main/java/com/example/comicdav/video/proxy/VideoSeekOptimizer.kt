package com.example.comicdav.video.proxy

import com.example.comicdav.core.diagnostics.DiagnosticCategory
import com.example.comicdav.core.diagnostics.Diagnostics
import com.example.comicdav.core.diagnostics.NoopDiagnostics
import com.example.comicdav.core.remote.ContentRange
import com.example.comicdav.core.remote.WebDavClient
import com.example.comicdav.core.remote.WebDavStreamResponse
import com.example.comicdav.core.model.settings.VideoProxySettings
import java.io.ByteArrayInputStream
import java.io.Closeable
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
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
    private val statsSink: VideoProxyStatsSink = VideoProxyStatsSink.Noop,
    private val diagnostics: Diagnostics = NoopDiagnostics,
) : Closeable {
    init {
        require(segmentBytes > 0L) { "segmentBytes must be positive" }
        require(smallRangeDirectThresholdBytes >= 0L) { "smallRangeDirectThresholdBytes must not be negative" }
    }

    private val closed = AtomicBoolean(false)
    private val inFlight = ConcurrentHashMap<VideoSegmentKey, InFlightSegment>()
    private val activeResponses = ConcurrentHashMap<VideoSegmentKey, MutableSet<Closeable>>()
    private val prefetchCoordinator = VideoPrefetchCoordinator(
        statsSink = statsSink,
        cancelInFlightSegments = ::cancelInFlightSegments,
    )

    suspend fun openRangeStream(
        client: WebDavClient,
        request: VideoStreamRequest,
        totalSize: Long,
        start: Long,
        endInclusive: Long,
        settings: VideoProxySettings,
        registerCancellation: (Closeable) -> Unit = {},
    ): WebDavStreamResponse {
        ensureOpen()
        require(totalSize >= 0L) { "totalSize must not be negative" }
        require(start >= 0L) { "start must not be negative" }
        require(endInclusive >= start) { "endInclusive must be greater than or equal to start" }
        require(endInclusive < totalSize) { "endInclusive must be inside totalSize" }

        if (!settings.seekOptimizationEnabled) {
            return openDirectRange(client, request, totalSize, start, endInclusive, registerCancellation)
        }

        val firstSegmentIndex = segmentIndexFor(start)
        val lastSegmentIndex = segmentIndexFor(endInclusive)
        val requestedBytes = checkedRequestedByteCount(start, endInclusive)
        val foregroundGeneration = prefetchCoordinator.beginForegroundGeneration(
            streamId = request.streamId,
            foregroundSegments = segmentIndexesBetween(firstSegmentIndex, lastSegmentIndex),
        )

        if (shouldStreamSmallRangeDirectly(firstSegmentIndex, lastSegmentIndex, requestedBytes)) {
            val cachedSlice = cache.getSegmentSliceReference(request.streamId, firstSegmentIndex, start, endInclusive)
            if (cachedSlice == null) {
                if (inFlight.containsKey(VideoSegmentKey(request.streamId, firstSegmentIndex))) {
                    val segment = getOrFetchSegment(
                        client = client,
                        request = request,
                        totalSize = totalSize,
                        segmentIndex = firstSegmentIndex,
                        waiterKind = SegmentWaiterKind.FOREGROUND,
                    )
                    val slice = segment.sliceReference(start, endInclusive)
                    return WebDavStreamResponse(
                        stream = ByteArraySlicesInputStream(listOf(slice)),
                        statusCode = 206,
                        contentLength = requestedBytes.toLong(),
                        contentRange = ContentRange(start, endInclusive, totalSize),
                        contentType = request.mimeType,
                        totalSize = totalSize,
                        close = {},
                    )
                }
                statsSink.updateDiagnosticMessage(request.streamId, "small_range_direct range=$start-$endInclusive")
                val directResponse = client.openRangeStream(
                    path = request.remotePath,
                    start = start,
                    endInclusive = endInclusive,
                    registerCancellation = registerCancellation,
                )
                statsSink.updateRemoteStatus(request.streamId, directResponse.statusCode)
                if (!isFullSegmentRange(firstSegmentIndex, totalSize, start, endInclusive)) {
                    scheduleSegmentWarmup(
                        client = client,
                        request = request,
                        totalSize = totalSize,
                        segmentIndex = firstSegmentIndex,
                        generation = foregroundGeneration,
                    )
                }
                return directResponse
            }
        }

        val slices = ArrayList<VideoRangeMemoryCache.SegmentSlice>()
        for (segmentSlice in segmentSlices(totalSize, start, endInclusive)) {
            val cachedSlice = cachedSegmentSliceOrNull(request, segmentSlice)
            if (cachedSlice != null) {
                slices += cachedSlice
            } else {
                val segment = getOrFetchSegment(
                    client = client,
                    request = request,
                    totalSize = totalSize,
                    segmentIndex = segmentSlice.segmentIndex,
                    waiterKind = SegmentWaiterKind.FOREGROUND,
                )
                slices += segment.sliceReference(segmentSlice.sliceStart, segmentSlice.sliceEnd)
            }
        }

        schedulePrefetch(
            client = client,
            request = request,
            totalSize = totalSize,
            highestSegmentIndex = lastSegmentIndex,
            settings = settings,
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

    suspend fun openStreamingRangeWithCacheWarmup(
        client: WebDavClient,
        request: VideoStreamRequest,
        totalSize: Long,
        start: Long,
        endInclusive: Long,
        settings: VideoProxySettings,
        registerCancellation: (Closeable) -> Unit = {},
    ): WebDavStreamResponse {
        ensureOpen()
        require(totalSize >= 0L) { "totalSize must not be negative" }
        require(start >= 0L) { "start must not be negative" }
        require(endInclusive >= start) { "endInclusive must be greater than or equal to start" }
        require(endInclusive < totalSize) { "endInclusive must be inside totalSize" }

        if (!settings.seekOptimizationEnabled) {
            return client.openRangeStream(
                path = request.remotePath,
                start = start,
                endInclusive = endInclusive,
                registerCancellation = registerCancellation,
            )
        }

        val firstSegmentIndex = segmentIndexFor(start)
        val lastSegmentIndex = segmentIndexFor(endInclusive)
        val foregroundSegments = segmentIndexesBetween(firstSegmentIndex, lastSegmentIndex)
        val foregroundGeneration = prefetchCoordinator.beginForegroundGeneration(
            streamId = request.streamId,
            foregroundSegments = foregroundSegments,
        )

        cachedRangeResponseOrNull(
            request = request,
            totalSize = totalSize,
            start = start,
            endInclusive = endInclusive,
        )?.let { cachedResponse ->
            schedulePrefetchSegments(
                client = client,
                request = request,
                totalSize = totalSize,
                segmentIndexes = openEndedForwardSegmentIndexes(
                    streamId = request.streamId,
                    foregroundSegmentCount = foregroundSegments.size,
                    highestSegmentIndex = lastSegmentIndex,
                    totalSize = totalSize,
                    settings = settings,
                ),
                generation = foregroundGeneration,
            )
            return cachedResponse
        }

        statsSink.updateDiagnosticMessage(request.streamId, "open_ended_direct range=$start-$endInclusive")
        val directResponse = client.openRangeStream(
            path = request.remotePath,
            start = start,
            endInclusive = endInclusive,
            registerCancellation = registerCancellation,
        )
        statsSink.updateRemoteStatus(request.streamId, directResponse.statusCode)
        val cachingResponse = directResponse.withSegmentCaching(
            request = request,
            totalSize = totalSize,
            start = start,
        )
        schedulePrefetchSegments(
            client = client,
            request = request,
            totalSize = totalSize,
            segmentIndexes = openEndedForwardSegmentIndexes(
                streamId = request.streamId,
                foregroundSegmentCount = foregroundSegments.size,
                highestSegmentIndex = lastSegmentIndex,
                totalSize = totalSize,
                settings = settings,
            ),
            generation = foregroundGeneration,
        )
        return cachingResponse
    }

    fun removeStream(streamId: String) {
        prefetchCoordinator.cancelStream(streamId)
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
        prefetchCoordinator.close()
        inFlight.values.forEach { it.deferred.cancel() }
        inFlight.clear()
        activeResponses.keys.toList().forEach(::closeActiveResponses)
        cache.clear()
    }

    private fun cachedRangeResponseOrNull(
        request: VideoStreamRequest,
        totalSize: Long,
        start: Long,
        endInclusive: Long,
    ): WebDavStreamResponse? {
        val requestedBytes = checkedRequestedByteCount(start, endInclusive)
        val slices = ArrayList<VideoRangeMemoryCache.SegmentSlice>()
        for (segmentSlice in segmentSlices(totalSize, start, endInclusive)) {
            val cachedSlice = cachedSegmentSliceOrNull(request, segmentSlice) ?: return null
            slices += cachedSlice
        }
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

    private fun segmentSlices(
        totalSize: Long,
        start: Long,
        endInclusive: Long,
    ): List<RequestedSegmentSlice> {
        val firstSegmentIndex = segmentIndexFor(start)
        val lastSegmentIndex = segmentIndexFor(endInclusive)
        val slices = ArrayList<RequestedSegmentSlice>()
        for (segmentIndex in firstSegmentIndex..lastSegmentIndex) {
            val segmentStart = segmentIndex * segmentBytes
            val segmentEnd = (segmentStart + segmentBytes - 1L).coerceAtMost(totalSize - 1L)
            slices += RequestedSegmentSlice(
                segmentIndex = segmentIndex,
                segmentStart = segmentStart,
                segmentEnd = segmentEnd,
                sliceStart = maxOf(start, segmentStart),
                sliceEnd = minOf(endInclusive, segmentEnd),
            )
        }
        return slices
    }

    private fun cachedSegmentSliceOrNull(
        request: VideoStreamRequest,
        segmentSlice: RequestedSegmentSlice,
    ): VideoRangeMemoryCache.SegmentSlice? {
        val cachedSlice = cache.getSegmentSliceReference(
            streamId = request.streamId,
            segmentIndex = segmentSlice.segmentIndex,
            start = segmentSlice.sliceStart,
            endInclusive = segmentSlice.sliceEnd,
        ) ?: return null
        statsSink.incrementCacheHit(request.streamId)
        return cachedSlice
    }

    private fun openEndedForwardSegmentIndexes(
        streamId: String,
        foregroundSegmentCount: Int,
        highestSegmentIndex: Long,
        totalSize: Long,
        settings: VideoProxySettings,
    ): List<Long> {
        val segmentIndexes = ArrayList<Long>()
        val forwardSegmentCount = foregroundSegmentCount.coerceAtLeast(1)
        // Open-ended proxy responses are bounded in local chunks (currently 8 MiB),
        // which span several cache segments. Treat STANDARD/AGGRESSIVE as chunk
        // counts here so sequential bytes=X- requests can be fully cache-covered.
        for (offset in 1..settings.forwardPrefetchMode.segmentCount) {
            for (segmentOffset in 1..forwardSegmentCount) {
                val segmentIndex = highestSegmentIndex + ((offset - 1) * forwardSegmentCount) + segmentOffset
                val segmentStart = segmentIndex * segmentBytes
                if (segmentStart >= totalSize) continue
                if (!cache.containsSegment(streamId, segmentIndex)) {
                    segmentIndexes += segmentIndex
                }
            }
        }
        return segmentIndexes.distinct()
    }

    private fun WebDavStreamResponse.withSegmentCaching(
        request: VideoStreamRequest,
        totalSize: Long,
        start: Long,
    ): WebDavStreamResponse {
        val upstreamClose = close
        val cachingStream = SegmentCachingInputStream(
            delegate = stream,
            request = request,
            totalSize = totalSize,
            firstByteOffset = start,
            segmentBytes = segmentBytes,
            cache = cache,
        )
        return copy(
            stream = cachingStream,
            close = {
                try {
                    cachingStream.close()
                } finally {
                    upstreamClose()
                }
            },
        )
    }

    private suspend fun openDirectRange(
        client: WebDavClient,
        request: VideoStreamRequest,
        totalSize: Long,
        start: Long,
        endInclusive: Long,
        registerCancellation: (Closeable) -> Unit,
    ): WebDavStreamResponse {
        val response = client.openRangeStream(
            path = request.remotePath,
            start = start,
            endInclusive = endInclusive,
            registerCancellation = registerCancellation,
        )
        statsSink.updateRemoteStatus(request.streamId, response.statusCode)
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
        waiterKind: SegmentWaiterKind,
    ): VideoRangeMemoryCache.Segment {
        cache.getSegmentReference(request.streamId, segmentIndex)?.let { segment ->
            statsSink.incrementCacheHit(request.streamId)
            return segment
        }

        val key = VideoSegmentKey(request.streamId, segmentIndex)
        var createdEntry: InFlightSegment? = null
        val inFlightSegment = inFlight.computeIfAbsent(key) {
            val deferred = coroutineScope.async(Dispatchers.IO, start = CoroutineStart.LAZY) {
                fetchSegment(
                    client = client,
                    request = request,
                    totalSize = totalSize,
                    segmentIndex = segmentIndex,
                )
            }
            InFlightSegment(deferred).also { newEntry ->
                createdEntry = newEntry
            }
        }
        createdEntry?.let { newEntry ->
            newEntry.deferred.invokeOnCompletion {
                inFlight.remove(key, newEntry)
            }
            newEntry.deferred.start()
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
    ): VideoRangeMemoryCache.Segment {
        cache.getSegmentReference(request.streamId, segmentIndex)?.let { segment ->
            statsSink.incrementCacheHit(request.streamId)
            return segment
        }

        val segmentStart = segmentIndex * segmentBytes
        val segmentEnd = (segmentStart + segmentBytes - 1L).coerceAtMost(totalSize - 1L)
        val expectedBytes = segmentEnd - segmentStart + 1L
        statsSink.updateDiagnosticMessage(request.streamId, "remote_fetch segment=$segmentIndex range=$segmentStart-$segmentEnd")

        val key = VideoSegmentKey(request.streamId, segmentIndex)
        var requestCloseable: Closeable? = null
        val response = try {
            client.openRangeStream(request.remotePath, segmentStart, segmentEnd) { closeable ->
                requestCloseable = closeable
                addActiveResponse(key, closeable)
            }
        } finally {
            requestCloseable?.let { removeActiveResponse(key, it) }
        }
        statsSink.updateRemoteStatus(request.streamId, response.statusCode)
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
            cache.putOwnedSegment(request.streamId, segmentIndex, segmentStart, bytes)
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
        generation: Long,
    ) {
        val segmentCount = settings.forwardPrefetchMode.segmentCount
        if (segmentCount <= 0 || closed.get()) return

        val segmentIndexes = ArrayList<Long>()
        for (offset in 1..segmentCount) {
            val segmentIndex = highestSegmentIndex + offset
            val segmentStart = segmentIndex * segmentBytes
            if (segmentStart >= totalSize) {
                continue
            }
            val key = VideoSegmentKey(request.streamId, segmentIndex)
            if (cache.containsSegment(request.streamId, segmentIndex)) {
                prefetchCoordinator.publishStateIfCurrent(request.streamId, generation, "skipped cache_hit")
                continue
            }
            if (inFlight.containsKey(key)) {
                prefetchCoordinator.publishStateIfCurrent(request.streamId, generation, "skipped inflight")
                continue
            }
            segmentIndexes += segmentIndex
        }

        schedulePrefetchSegments(
            client = client,
            request = request,
            totalSize = totalSize,
            segmentIndexes = segmentIndexes,
            generation = generation,
        )
    }

    private fun scheduleSegmentWarmup(
        client: WebDavClient,
        request: VideoStreamRequest,
        totalSize: Long,
        segmentIndex: Long,
        generation: Long,
    ) {
        if (closed.get()) return
        val key = VideoSegmentKey(request.streamId, segmentIndex)
        if (cache.containsSegment(request.streamId, segmentIndex) || inFlight.containsKey(key)) return
        schedulePrefetchSegments(
            client = client,
            request = request,
            totalSize = totalSize,
            segmentIndexes = listOf(segmentIndex),
            generation = generation,
        )
    }

    private fun schedulePrefetchSegments(
        client: WebDavClient,
        request: VideoStreamRequest,
        totalSize: Long,
        segmentIndexes: List<Long>,
        generation: Long,
    ) {
        if (segmentIndexes.isEmpty() || closed.get()) return

        val keys = segmentIndexes.map { VideoSegmentKey(request.streamId, it) }
        lateinit var job: Job
        job = coroutineScope.launch(Dispatchers.IO, start = CoroutineStart.LAZY) {
            try {
                for (segmentIndex in segmentIndexes) {
                    coroutineContext.ensureActive()
                    if (!prefetchCoordinator.isCurrentGeneration(request.streamId, generation)) return@launch
                    val key = VideoSegmentKey(request.streamId, segmentIndex)
                    if (cache.containsSegment(request.streamId, segmentIndex)) {
                        prefetchCoordinator.publishStateIfCurrent(
                            request.streamId,
                            generation,
                            "skipped cache_hit",
                            job,
                        )
                        continue
                    }
                    if (inFlight.containsKey(key)) {
                        prefetchCoordinator.publishStateIfCurrent(
                            request.streamId,
                            generation,
                            "skipped inflight",
                            job,
                        )
                        continue
                    }
                    getOrFetchSegment(
                        client = client,
                        request = request,
                        totalSize = totalSize,
                        segmentIndex = segmentIndex,
                        waiterKind = SegmentWaiterKind.PREFETCH,
                    )
                }
                if (
                    segmentIndexes.all { segmentIndex -> cache.containsSegment(request.streamId, segmentIndex) }
                ) {
                    val completedSegments = segmentIndexes.joinToString(",")
                    prefetchCoordinator.finishJob(
                        streamId = request.streamId,
                        generation = generation,
                        job = job,
                        finalState = "completed $completedSegments",
                    )
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                diagnostics.error(
                    DiagnosticCategory.VIDEO,
                    "video_proxy_prefetch_failed stream=${VideoProxyDiagnostics.redactedStreamId(request.streamId)}",
                    error,
                )
                prefetchCoordinator.finishJob(
                    streamId = request.streamId,
                    generation = generation,
                    job = job,
                    finalState = "failed ${error.javaClass.simpleName}",
                )
            } finally {
                prefetchCoordinator.unregisterJob(request.streamId, generation, job)
            }
        }

        val scheduledSegments = segmentIndexes.joinToString(",")
        if (
            prefetchCoordinator.registerJob(
                request.streamId,
                generation,
                keys,
                job,
                "scheduled $scheduledSegments",
            )
        ) {
            job.start()
        } else {
            job.cancel()
        }
    }

    private fun cancelInFlightSegments(keys: List<VideoSegmentKey>) {
        keys.forEach { key ->
            val entry = inFlight[key]
            if (entry != null && entry.foregroundWaiters.get() <= 0) {
                inFlight.remove(key, entry)
                entry.deferred.cancel()
                closeActiveResponses(key)
            }
        }
    }

    private fun addActiveResponse(key: VideoSegmentKey, closeable: Closeable): Boolean {
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

    private fun removeActiveResponse(key: VideoSegmentKey, closeable: Closeable) {
        val responses = activeResponses[key] ?: return
        responses -= closeable
        if (responses.isEmpty()) {
            activeResponses.remove(key, responses)
        }
    }

    private fun closeActiveResponses(key: VideoSegmentKey) {
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

    private fun Closeable.closeQuietly() {
        runCatching { close() }
    }

    private companion object {
        const val DEFAULT_SMALL_RANGE_DIRECT_THRESHOLD_BYTES: Long = 256L * 1024L
    }
}
