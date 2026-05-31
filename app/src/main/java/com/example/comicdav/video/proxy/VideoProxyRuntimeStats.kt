package com.example.comicdav.video.proxy

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

data class VideoProxyRuntimeStats(
    val currentRange: String?,
    val remoteHttpStatus: Int?,
    val memoryCacheHits: Long,
    val prefetchState: String?,
    val diagnosticMessage: String?,
)

internal interface VideoProxyStatsSink {
    fun registerStream(streamId: String)
    fun removeStream(streamId: String)
    fun clear()
    fun updateCurrentRange(streamId: String, range: String?)
    fun updateRemoteStatus(streamId: String, statusCode: Int?)
    fun incrementCacheHit(streamId: String)
    fun updatePrefetchState(streamId: String, state: String?)
    fun updateDiagnosticMessage(streamId: String, message: String?)
    fun snapshot(streamId: String): VideoProxyRuntimeStats?

    object Noop : VideoProxyStatsSink {
        override fun registerStream(streamId: String) = Unit
        override fun removeStream(streamId: String) = Unit
        override fun clear() = Unit
        override fun updateCurrentRange(streamId: String, range: String?) = Unit
        override fun updateRemoteStatus(streamId: String, statusCode: Int?) = Unit
        override fun incrementCacheHit(streamId: String) = Unit
        override fun updatePrefetchState(streamId: String, state: String?) = Unit
        override fun updateDiagnosticMessage(streamId: String, message: String?) = Unit
        override fun snapshot(streamId: String): VideoProxyRuntimeStats? = null
    }
}

internal class VideoProxyStatsStore : VideoProxyStatsSink {
    private val streams = ConcurrentHashMap<String, MutableVideoProxyRuntimeStats>()

    override fun registerStream(streamId: String) {
        streams.putIfAbsent(streamId, MutableVideoProxyRuntimeStats())
    }

    override fun removeStream(streamId: String) {
        streams.remove(streamId)
    }

    override fun clear() {
        streams.clear()
    }

    override fun updateCurrentRange(streamId: String, range: String?) {
        streams[streamId]?.currentRange = range
    }

    override fun updateRemoteStatus(streamId: String, statusCode: Int?) {
        streams[streamId]?.remoteHttpStatus = statusCode
    }

    override fun incrementCacheHit(streamId: String) {
        streams[streamId]?.memoryCacheHits?.incrementAndGet()
    }

    override fun updatePrefetchState(streamId: String, state: String?) {
        streams[streamId]?.prefetchState = state
    }

    override fun updateDiagnosticMessage(streamId: String, message: String?) {
        streams[streamId]?.diagnosticMessage = message
    }

    override fun snapshot(streamId: String): VideoProxyRuntimeStats? =
        streams[streamId]?.snapshot()

    private class MutableVideoProxyRuntimeStats {
        @Volatile
        var currentRange: String? = null
        @Volatile
        var remoteHttpStatus: Int? = null
        val memoryCacheHits = AtomicLong(0L)
        @Volatile
        var prefetchState: String? = null
        @Volatile
        var diagnosticMessage: String? = null

        fun snapshot(): VideoProxyRuntimeStats =
            VideoProxyRuntimeStats(
                currentRange = currentRange,
                remoteHttpStatus = remoteHttpStatus,
                memoryCacheHits = memoryCacheHits.get(),
                prefetchState = prefetchState,
                diagnosticMessage = diagnosticMessage,
            )
    }
}
