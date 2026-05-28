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
        requireValidRange(start, endInclusive)
        return provider(fileId).readRange(fileId, start, endInclusive)
    }

    @JvmStatic
    fun isRangeCached(fileId: Long, start: Long, endInclusive: Long): Boolean {
        requireValidRange(start, endInclusive)
        return provider(fileId).isRangeCached(start, endInclusive)
    }

    @JvmStatic
    fun readCachedRange(fileId: Long, start: Long, endInclusive: Long): ByteArray? {
        requireValidRange(start, endInclusive)
        return provider(fileId).readCachedRange(start, endInclusive)
    }

    @JvmStatic
    fun prefetchRange(fileId: Long, start: Long, endInclusive: Long): Boolean {
        requireValidRange(start, endInclusive)
        return provider(fileId).prefetchRange(start, endInclusive)
    }

    @JvmStatic
    fun prefetchRange(
        fileId: Long,
        start: Long,
        endInclusive: Long,
        priority: Int,
        protectedRanges: List<LongRange>,
    ): Boolean {
        requireValidRange(start, endInclusive)
        protectedRanges.forEach { range -> requireValidRange(range.first, range.last) }
        return provider(fileId).prefetchRange(start, endInclusive, priority, protectedRanges)
    }

    private fun requireValidRange(start: Long, endInclusive: Long) {
        require(start >= 0) { "Range start must be non-negative" }
        require(endInclusive >= start) { "Range end must be >= start" }
    }

    private fun provider(fileId: Long): RangeProvider =
        providers[fileId] ?: throw IllegalArgumentException("Range provider not found: $fileId")
}
