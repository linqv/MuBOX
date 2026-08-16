package org.mubox.reader.nativebridge

import org.mubox.reader.core.ports.RangeProvider
import java.nio.ByteBuffer
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
        providers.remove(fileId)?.close()
    }

    /** Fills one native-owned direct buffer without allocating a range-sized Java array. */
    @JvmStatic
    fun fetchRangeIntoV1(
        fileId: Long,
        requestId: Long,
        start: Long,
        endInclusive: Long,
        target: ByteBuffer,
    ): Int {
        require(requestId > 0L) { "Range request id must be positive" }
        requireValidRange(start, endInclusive)
        require(target.isDirect) { "Range request target must be a direct ByteBuffer" }
        require(!target.isReadOnly) { "Range request target must be writable" }
        val expectedBytes = endInclusive - start + 1L
        require(expectedBytes in 1..Int.MAX_VALUE.toLong()) { "Range request is too large" }
        require(
            target.position() == 0 &&
                target.limit() == expectedBytes.toInt() &&
                target.capacity() == expectedBytes.toInt()
        ) { "Range target has the wrong bounds" }
        val written = provider(fileId).fetchRangeInto(
            fileId = fileId,
            requestId = requestId,
            start = start,
            endInclusive = endInclusive,
            target = target,
        )
        require(written == expectedBytes.toInt()) { "Range response has the wrong size" }
        require(target.position() == expectedBytes.toInt()) { "Range target was not fully written" }
        return written
    }

    @JvmStatic
    fun cancelRangeFetchV1(fileId: Long, requestId: Long) {
        require(requestId > 0L) { "Range request id must be positive" }
        providers[fileId]?.cancelRangeRequest(requestId)
    }

    private fun requireValidRange(start: Long, endInclusive: Long) {
        require(start >= 0) { "Range start must be non-negative" }
        require(endInclusive >= start) { "Range end must be >= start" }
    }

    private fun provider(fileId: Long): RangeProvider =
        providers[fileId] ?: throw IllegalArgumentException("Range provider not found: $fileId")
}
