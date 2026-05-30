package com.example.comicdav.video.proxy

class VideoRangeMemoryCache(
    private val maxBytes: Long = DEFAULT_MAX_BYTES,
) {
    init {
        require(maxBytes >= 0L) { "maxBytes must not be negative" }
    }

    private val lock = Any()
    private val entries = LinkedHashMap<SegmentKey, Segment>(16, 0.75f, true)
    private var byteCount = 0L

    fun getSegment(streamId: String, segmentIndex: Long): Segment? = synchronized(lock) {
        entries[SegmentKey(streamId, segmentIndex)]?.snapshot()
    }

    internal fun getSegmentReference(streamId: String, segmentIndex: Long): Segment? = synchronized(lock) {
        entries[SegmentKey(streamId, segmentIndex)]
    }

    fun getSegmentSlice(
        streamId: String,
        segmentIndex: Long,
        start: Long,
        endInclusive: Long,
    ): ByteArray? = synchronized(lock) {
        entries[SegmentKey(streamId, segmentIndex)]?.slice(start, endInclusive)
    }

    fun putSegment(streamId: String, segmentIndex: Long, start: Long, bytes: ByteArray): Boolean = synchronized(lock) {
        require(segmentIndex >= 0L) { "segmentIndex must not be negative" }
        require(start >= 0L) { "start must not be negative" }
        if (bytes.size.toLong() > maxBytes) return false

        val key = SegmentKey(streamId, segmentIndex)
        entries.remove(key)?.let { byteCount -= it.bytes.size.toLong() }
        entries[key] = Segment(
            streamId = streamId,
            segmentIndex = segmentIndex,
            start = start,
            bytes = bytes.copyOf(),
        )
        byteCount += bytes.size.toLong()
        trimToSize()
        true
    }

    fun containsSegment(streamId: String, segmentIndex: Long): Boolean = synchronized(lock) {
        entries.containsKey(SegmentKey(streamId, segmentIndex))
    }

    fun removeStream(streamId: String) = synchronized(lock) {
        val iterator = entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (entry.key.streamId == streamId) {
                byteCount -= entry.value.bytes.size.toLong()
                iterator.remove()
            }
        }
    }

    fun clear() = synchronized(lock) {
        entries.clear()
        byteCount = 0L
    }

    fun totalBytes(): Long = synchronized(lock) { byteCount }

    fun segmentCount(): Int = synchronized(lock) { entries.size }

    private fun trimToSize() {
        val iterator = entries.iterator()
        while (byteCount > maxBytes && iterator.hasNext()) {
            val entry = iterator.next()
            byteCount -= entry.value.bytes.size.toLong()
            iterator.remove()
        }
    }

    class Segment(
        val streamId: String,
        val segmentIndex: Long,
        val start: Long,
        val bytes: ByteArray,
    ) {
        val endInclusive: Long
            get() = start + bytes.size - 1L

        fun slice(start: Long, endInclusive: Long): ByteArray {
            require(start >= this.start) { "slice start is before segment start" }
            require(endInclusive <= this.endInclusive) { "slice end is after segment end" }
            require(start <= endInclusive) { "slice range must not be empty" }
            val from = (start - this.start).toInt()
            val toExclusive = (endInclusive - this.start + 1L).toInt()
            return bytes.copyOfRange(from, toExclusive)
        }

        internal fun snapshot(): Segment =
            Segment(
                streamId = streamId,
                segmentIndex = segmentIndex,
                start = start,
                bytes = bytes.copyOf(),
            )

        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Segment) return false
            return streamId == other.streamId &&
                segmentIndex == other.segmentIndex &&
                start == other.start &&
                bytes.contentEquals(other.bytes)
        }

        override fun hashCode(): Int {
            var result = streamId.hashCode()
            result = 31 * result + segmentIndex.hashCode()
            result = 31 * result + start.hashCode()
            result = 31 * result + bytes.contentHashCode()
            return result
        }
    }

    private data class SegmentKey(
        val streamId: String,
        val segmentIndex: Long,
    )

    companion object {
        const val DEFAULT_SEGMENT_BYTES: Long = 2L * 1024L * 1024L
        const val DEFAULT_MAX_BYTES: Long = 64L * 1024L * 1024L
    }
}
