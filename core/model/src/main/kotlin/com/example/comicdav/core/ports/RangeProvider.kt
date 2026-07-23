package com.example.comicdav.core.ports

interface RangeProvider {
    fun size(fileId: Long): Long
    fun readRange(fileId: Long, start: Long, endInclusive: Long): ByteArray
    fun isRangeCached(start: Long, endInclusive: Long): Boolean = false
    fun readCachedRange(start: Long, endInclusive: Long): ByteArray? = null
    fun prefetchRange(start: Long, endInclusive: Long): Boolean = false
    fun prefetchRange(
        start: Long,
        endInclusive: Long,
        priority: Int,
        protectedRanges: List<LongRange>,
    ): Boolean = prefetchRange(start, endInclusive)
    fun cancelPrefetches() = Unit
    fun close() = Unit
}
