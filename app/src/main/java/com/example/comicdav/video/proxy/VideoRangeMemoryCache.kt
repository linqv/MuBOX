package com.example.comicdav.video.proxy

class VideoRangeMemoryCache {
    private val cache = linkedMapOf<String, ByteArray>()

    fun get(key: String): ByteArray? = cache[key]

    fun put(key: String, value: ByteArray) {
        cache[key] = value
    }
}
