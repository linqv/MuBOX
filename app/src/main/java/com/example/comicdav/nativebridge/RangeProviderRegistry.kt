package com.example.comicdav.nativebridge

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

object RangeProviderRegistry {
    private val nextFileId = AtomicLong(1)
    private val providers = ConcurrentHashMap<Long, RangeProvider>()

    @JvmStatic
    fun register(provider: RangeProvider): Long {
        val fileId = nextFileId.getAndIncrement()
        require(fileId > 0) { "Range provider id overflowed" }
        providers[fileId] = provider
        return fileId
    }

    @JvmStatic
    fun unregister(fileId: Long) {
        providers.remove(fileId)
    }

    @JvmStatic
    fun size(fileId: Long): Long = provider(fileId).size(fileId)

    @JvmStatic
    fun readRange(fileId: Long, start: Long, endInclusive: Long): ByteArray {
        require(start >= 0) { "Range start must be non-negative" }
        require(endInclusive >= start) { "Range end must be >= start" }
        return provider(fileId).readRange(fileId, start, endInclusive)
    }

    @JvmStatic
    fun prefetchRange(fileId: Long, start: Long, endInclusive: Long): Boolean {
        require(start >= 0) { "Range start must be non-negative" }
        require(endInclusive >= start) { "Range end must be >= start" }
        return provider(fileId).prefetchRange(start, endInclusive)
    }

    private fun provider(fileId: Long): RangeProvider =
        providers[fileId] ?: throw IllegalArgumentException("Range provider not found: $fileId")
}
