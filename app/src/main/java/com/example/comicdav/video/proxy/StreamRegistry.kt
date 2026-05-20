package com.example.comicdav.video.proxy

import java.io.Closeable
import java.util.concurrent.ConcurrentHashMap

class StreamRegistry : Closeable {
    private val entries = ConcurrentHashMap<String, VideoStreamRequest>()

    fun put(streamId: String, request: VideoStreamRequest) {
        entries[streamId] = request
    }

    fun get(streamId: String): VideoStreamRequest? = entries[streamId]

    fun remove(streamId: String): VideoStreamRequest? = entries.remove(streamId)

    override fun close() {
        entries.clear()
    }

    fun closeAll() {
        close()
    }
}
