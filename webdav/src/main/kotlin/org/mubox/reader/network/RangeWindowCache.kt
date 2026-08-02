package org.mubox.reader.network

/**
 * A byte-bounded cache of non-overlapping remote file segments.
 *
 * The cache composes adjacent segments for reads and uses access order for eviction.
 * Callers are responsible for synchronization.
 */
internal class RangeWindowCache(
    private val maxBytes: Long,
    private val segmentBytes: Int = DEFAULT_SEGMENT_BYTES,
) {
    private val segments = mutableListOf<Segment>()
    private var cachedBytes = 0L
    private var sequence = 0L

    init {
        require(segmentBytes > 0) { "segmentBytes must be positive" }
    }

    fun find(start: Long, endInclusive: Long): LookupResult? {
        val coveredBy = coveringSegments(start, endInclusive) ?: return null
        val lastAccess = ++sequence
        coveredBy.forEach { segment -> segment.lastAccess = lastAccess }
        val result = ByteArray(rangeLength(start, endInclusive).toInt())
        coveredBy.forEach { segment ->
            val copyStart = maxOf(start, segment.start)
            val copyEndInclusive = minOf(endInclusive, segment.endInclusive)
            segment.bytes.copyInto(
                destination = result,
                destinationOffset = (copyStart - start).toInt(),
                startIndex = (copyStart - segment.start).toInt(),
                endIndex = (copyEndInclusive - segment.start + 1).toInt(),
            )
        }
        return LookupResult(
            bytes = result,
            windowStart = coveredBy.first().start,
            windowEndInclusive = coveredBy.last().endInclusive,
        )
    }

    fun isCovered(start: Long, endInclusive: Long): Boolean =
        coveringSegments(start, endInclusive) != null

    fun store(
        start: Long,
        endInclusive: Long,
        bytes: ByteArray,
        protectedRanges: List<LongRange> = emptyList(),
    ): StoreResult {
        require(rangeLength(start, endInclusive) == bytes.size.toLong()) {
            "Range metadata does not match byte count"
        }
        if (bytes.size.toLong() > maxBytes) {
            return StoreResult(stored = false, skippedReason = "oversized", evictionMode = "none")
        }
        val retained = segmentsOutside(start, endInclusive)
        val incoming = splitIntoSegments(start, bytes, ++sequence)
        if (protectedRanges.isNotEmpty()) {
            return commitWithEviction(retained, incoming, protectedRanges, evictionMode = "protected")
        }
        return commitWithEviction(retained, incoming, protectedRanges = emptyList(), evictionMode = "lru")
    }

    fun windowCount(): Int = segments.size

    fun totalBytes(): Long = cachedBytes

    private fun coveringSegments(start: Long, endInclusive: Long): List<Segment>? {
        if (endInclusive < start) return null
        var cursor = start
        val coveredBy = mutableListOf<Segment>()
        for (segment in segments) {
            if (segment.endInclusive < cursor) continue
            if (segment.start > cursor) return null
            coveredBy += segment
            if (segment.endInclusive >= endInclusive) return coveredBy
            cursor = segment.endInclusive + 1
        }
        return null
    }

    private fun commitWithEviction(
        retained: List<Segment>,
        incoming: List<Segment>,
        protectedRanges: List<LongRange>,
        evictionMode: String,
    ): StoreResult {
        var projectedBytes = retained.sumOf { it.bytes.size.toLong() } +
            incoming.sumOf { it.bytes.size.toLong() }
        val evictedSegments = mutableListOf<Segment>()
        val candidates = retained
            .filterNot { it.intersectsAny(protectedRanges) }
            .sortedBy { it.lastAccess }
        for (candidate in candidates) {
            if (projectedBytes <= maxBytes) break
            projectedBytes -= candidate.bytes.size.toLong()
            evictedSegments += candidate
        }
        if (projectedBytes > maxBytes) {
            return StoreResult(
                stored = false,
                skippedReason = "protected_capacity",
                evictionMode = evictionMode,
            )
        }
        val evictedSet = evictedSegments.toSet()
        segments.clear()
        segments += retained.filterNot { it in evictedSet }
        segments += incoming
        segments.sortBy { it.start }
        cachedBytes = projectedBytes
        return StoreResult(
            stored = true,
            evicted = evictedSegments.map { it.snapshot() },
            evictionMode = evictionMode,
        )
    }

    // Replacing an overlapping range copies at most the two edge fragments. Adjacent
    // segments remain independent, so growing the cache never recopies prior contents.
    private fun segmentsOutside(start: Long, endInclusive: Long): List<Segment> =
        buildList {
            segments.forEach { segment ->
                if (!segment.intersects(start, endInclusive)) {
                    add(segment)
                    return@forEach
                }
                if (segment.start < start) {
                    add(segment.slice(segment.start, start - 1))
                }
                if (segment.endInclusive > endInclusive) {
                    add(segment.slice(endInclusive + 1, segment.endInclusive))
                }
            }
        }

    private fun splitIntoSegments(start: Long, bytes: ByteArray, lastAccess: Long): List<Segment> =
        buildList {
            var offset = 0
            while (offset < bytes.size) {
                val length = minOf(segmentBytes, bytes.size - offset)
                val segmentData = if (offset == 0 && length == bytes.size) {
                    bytes
                } else {
                    bytes.copyOfRange(offset, offset + length)
                }
                val segmentStart = start + offset
                add(
                    Segment(
                        start = segmentStart,
                        endInclusive = segmentStart + length - 1,
                        bytes = segmentData,
                        lastAccess = lastAccess,
                    ),
                )
                offset += length
            }
        }

    private fun rangeLength(start: Long, endInclusive: Long): Long {
        require(endInclusive >= start) { "Range end must not precede start" }
        return endInclusive - start + 1
    }

    internal data class LookupResult(
        val bytes: ByteArray,
        val windowStart: Long,
        val windowEndInclusive: Long,
    )

    internal data class StoreResult(
        val stored: Boolean,
        val skippedReason: String? = null,
        val evicted: List<WindowSnapshot> = emptyList(),
        val evictionMode: String = "lru",
    )

    internal data class WindowSnapshot(
        val start: Long,
        val endInclusive: Long,
        val bytes: Int,
    )

    private class Segment(
        val start: Long,
        val endInclusive: Long,
        val bytes: ByteArray,
        var lastAccess: Long,
    ) {
        fun intersectsAny(ranges: List<LongRange>): Boolean =
            ranges.any { range ->
                !range.isEmpty() && start <= range.last && endInclusive >= range.first
            }

        fun intersects(reqStart: Long, reqEnd: Long): Boolean =
            start <= reqEnd && endInclusive >= reqStart

        fun slice(reqStart: Long, reqEnd: Long): Segment {
            val from = (reqStart - start).toInt()
            val toExclusive = (reqEnd - start + 1).toInt()
            return Segment(
                start = reqStart,
                endInclusive = reqEnd,
                bytes = bytes.copyOfRange(from, toExclusive),
                lastAccess = lastAccess,
            )
        }

        fun snapshot(): WindowSnapshot =
            WindowSnapshot(start = start, endInclusive = endInclusive, bytes = bytes.size)
    }

    private companion object {
        const val DEFAULT_SEGMENT_BYTES = 4 * 1024 * 1024
    }
}
