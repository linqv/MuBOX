package org.mubox.reader.video.proxy

import java.io.Closeable
import java.util.concurrent.ConcurrentHashMap

internal class StreamRegistry : Closeable {
    private val entries = ConcurrentHashMap<String, RegisteredVideoStream>()
    private val activeStreams = ConcurrentHashMap<String, MutableSet<Closeable>>()

    fun put(streamId: String, stream: RegisteredVideoStream) {
        entries[streamId] = stream
    }

    fun get(streamId: String): RegisteredVideoStream? = entries[streamId]

    fun remove(streamId: String): RegisteredVideoStream? {
        val removed = entries.remove(streamId)
        closeActive(streamId)
        return removed
    }

    fun addActive(streamId: String, closeable: Closeable): Boolean {
        if (!entries.containsKey(streamId)) {
            closeQuietly(closeable)
            return false
        }
        val activeForStream = activeStreams.computeIfAbsent(streamId) {
            ConcurrentHashMap.newKeySet()
        }
        activeForStream += closeable
        if (!entries.containsKey(streamId)) {
            removeActive(streamId, closeable)
            closeQuietly(closeable)
            return false
        }
        return true
    }

    fun removeActive(streamId: String, closeable: Closeable) {
        val activeForStream = activeStreams[streamId] ?: return
        activeForStream -= closeable
        if (activeForStream.isEmpty()) {
            activeStreams.remove(streamId, activeForStream)
        }
    }

    override fun close() {
        entries.clear()
        activeStreams.keys.toList().forEach(::closeActive)
    }

    fun closeAll() {
        close()
    }

    private fun closeActive(streamId: String) {
        activeStreams.remove(streamId)?.forEach(::closeQuietly)
    }

    private fun closeQuietly(closeable: Closeable) {
        runCatching { closeable.close() }
    }
}
