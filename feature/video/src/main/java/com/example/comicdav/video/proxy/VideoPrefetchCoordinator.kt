package com.example.comicdav.video.proxy

import java.io.Closeable
import kotlinx.coroutines.Job

internal data class VideoSegmentKey(
    val streamId: String,
    val segmentIndex: Long,
)

/**
 * Owns the per-stream prefetch generation and job lifecycle.
 *
 * Fetching remains the optimizer's responsibility. This coordinator only decides whether a job
 * still belongs to the current foreground generation and which stale in-flight segments may be
 * cancelled when playback moves elsewhere.
 */
internal class VideoPrefetchCoordinator(
    private val statsSink: VideoProxyStatsSink,
    private val cancelInFlightSegments: (List<VideoSegmentKey>) -> Unit,
) : Closeable {
    private val lock = Any()
    private val states = mutableMapOf<String, PrefetchState>()

    fun beginForegroundGeneration(
        streamId: String,
        foregroundSegments: Set<Long>,
    ): Long {
        val (generation, cancellation) = synchronized(lock) {
            val state = states.getOrPut(streamId) { PrefetchState() }
            state.generation += 1L
            val overlapsForeground = state.keys.any { key -> key.segmentIndex in foregroundSegments }
            val cancellation = if (overlapsForeground) {
                PrefetchCancellation(null, emptyList())
            } else {
                state.copyForCancellation()
            }
            state.job = null
            state.keys.clear()
            statsSink.updatePrefetchState(streamId, null)
            state.generation to cancellation
        }
        cancel(cancellation)
        return generation
    }

    fun registerJob(
        streamId: String,
        generation: Long,
        keys: List<VideoSegmentKey>,
        job: Job,
        scheduledState: String,
    ): Boolean =
        synchronized(lock) {
            val state = states.getOrPut(streamId) { PrefetchState() }
            if (state.generation != generation) return@synchronized false
            state.job?.cancel()
            state.job = job
            state.keys.clear()
            state.keys += keys
            statsSink.updatePrefetchState(streamId, scheduledState)
            true
        }

    fun publishStateIfCurrent(
        streamId: String,
        generation: Long,
        prefetchState: String,
        job: Job? = null,
    ): Boolean =
        synchronized(lock) {
            val state = states[streamId] ?: return@synchronized false
            if (state.generation != generation) return@synchronized false
            if (job != null && state.job !== job) return@synchronized false
            statsSink.updatePrefetchState(streamId, prefetchState)
            true
        }

    fun finishJob(
        streamId: String,
        generation: Long,
        job: Job,
        finalState: String,
    ): Boolean =
        synchronized(lock) {
            val state = states[streamId] ?: return@synchronized false
            if (state.generation != generation || state.job !== job) return@synchronized false
            state.job = null
            state.keys.clear()
            statsSink.updatePrefetchState(streamId, finalState)
            true
        }

    fun unregisterJob(streamId: String, generation: Long, job: Job) {
        synchronized(lock) {
            val state = states[streamId] ?: return
            if (state.generation != generation || state.job !== job) return
            state.job = null
            state.keys.clear()
        }
    }

    fun cancelStream(streamId: String) {
        val cancellation = synchronized(lock) {
            states.remove(streamId)?.copyForCancellation()
        }
        cancellation?.let(::cancel)
        statsSink.updatePrefetchState(streamId, null)
    }

    fun isCurrentGeneration(streamId: String, generation: Long): Boolean =
        synchronized(lock) {
            states[streamId]?.generation == generation
        }

    override fun close() {
        val cancellations = synchronized(lock) {
            states.values.map { state -> state.copyForCancellation() }.also { states.clear() }
        }
        cancellations.forEach(::cancel)
    }

    private fun cancel(cancellation: PrefetchCancellation) {
        cancellation.job?.cancel()
        if (cancellation.keys.isNotEmpty()) {
            cancelInFlightSegments(cancellation.keys)
        }
    }

    private class PrefetchState {
        var generation: Long = 0L
        var job: Job? = null
        val keys: MutableSet<VideoSegmentKey> = mutableSetOf()

        fun copyForCancellation(): PrefetchCancellation =
            PrefetchCancellation(job, keys.toList())
    }

    private data class PrefetchCancellation(
        val job: Job?,
        val keys: List<VideoSegmentKey>,
    )
}
