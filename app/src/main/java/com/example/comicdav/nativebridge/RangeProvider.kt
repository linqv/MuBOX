package com.example.comicdav.nativebridge

interface RangeProvider {
    fun size(fileId: Long): Long
    fun readRange(fileId: Long, start: Long, endInclusive: Long): ByteArray
    fun prefetchRange(start: Long, endInclusive: Long): Boolean = false
    fun prefetchRange(
        start: Long,
        endInclusive: Long,
        priority: Int,
        protectedRanges: List<LongRange>,
    ): Boolean = prefetchRange(start, endInclusive)
}
