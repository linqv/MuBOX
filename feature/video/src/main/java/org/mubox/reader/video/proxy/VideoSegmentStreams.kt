package org.mubox.reader.video.proxy

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Deferred

internal data class RequestedSegmentSlice(
    val segmentIndex: Long,
    val segmentStart: Long,
    val segmentEnd: Long,
    val sliceStart: Long,
    val sliceEnd: Long,
)

internal class InFlightSegment(
    val deferred: Deferred<VideoRangeMemoryCache.Segment>,
) {
    val foregroundWaiters = AtomicInteger(0)
    private val prefetchWaiters = AtomicInteger(0)

    fun increment(kind: SegmentWaiterKind) {
        when (kind) {
            SegmentWaiterKind.FOREGROUND -> foregroundWaiters.incrementAndGet()
            SegmentWaiterKind.PREFETCH -> prefetchWaiters.incrementAndGet()
        }
    }

    fun decrement(kind: SegmentWaiterKind) {
        when (kind) {
            SegmentWaiterKind.FOREGROUND -> foregroundWaiters.decrementAndGet()
            SegmentWaiterKind.PREFETCH -> prefetchWaiters.decrementAndGet()
        }
    }
}

internal enum class SegmentWaiterKind {
    FOREGROUND,
    PREFETCH,
}

internal class ByteArraySlicesInputStream(
    slices: List<VideoRangeMemoryCache.SegmentSlice>,
) : InputStream() {
    private val slices = slices.filter { it.size > 0 }
    private var sliceIndex = 0
    private var offset = 0

    override fun read(): Int {
        while (sliceIndex < slices.size) {
            val slice = slices[sliceIndex]
            if (offset < slice.size) {
                return slice.bytes[slice.fromIndex + offset++].toInt() and 0xff
            }
            sliceIndex += 1
            offset = 0
        }
        return -1
    }

    override fun read(buffer: ByteArray, off: Int, len: Int): Int {
        if (len == 0) return 0
        var total = 0
        var outputOffset = off
        var remaining = len
        while (remaining > 0 && sliceIndex < slices.size) {
            val slice = slices[sliceIndex]
            if (offset >= slice.size) {
                sliceIndex += 1
                offset = 0
                continue
            }
            val count = minOf(remaining, slice.size - offset)
            slice.bytes.copyInto(
                destination = buffer,
                destinationOffset = outputOffset,
                startIndex = slice.fromIndex + offset,
                endIndex = slice.fromIndex + offset + count,
            )
            offset += count
            outputOffset += count
            remaining -= count
            total += count
        }
        return if (total == 0) -1 else total
    }
}

internal class SegmentCachingInputStream(
    private val delegate: InputStream,
    private val request: VideoStreamRequest,
    private val totalSize: Long,
    private val firstByteOffset: Long,
    private val segmentBytes: Long,
    private val cache: VideoRangeMemoryCache,
) : InputStream() {
    private var nextOffset = firstByteOffset
    private var segmentIndex = firstByteOffset / segmentBytes
    private var segmentStart = segmentIndex * segmentBytes
    private var segmentBuffer = ByteArrayOutputStream()
    private var closed = false

    override fun read(): Int {
        val value = delegate.read()
        if (value >= 0) {
            recordByte(value.toByte())
        } else {
            flushCompletedSegmentAtEndOfStream()
        }
        return value
    }

    override fun read(buffer: ByteArray, off: Int, len: Int): Int {
        val count = delegate.read(buffer, off, len)
        if (count > 0) {
            recordBytes(buffer, off, count)
        } else if (count == -1) {
            flushCompletedSegmentAtEndOfStream()
        }
        return count
    }

    override fun close() {
        if (closed) return
        closed = true
        delegate.close()
    }

    private fun recordByte(byte: Byte) {
        segmentBuffer.write(byte.toInt())
        nextOffset += 1L
        finishSegmentIfBoundaryReached()
    }

    private fun recordBytes(buffer: ByteArray, off: Int, len: Int) {
        var sourceOffset = off
        var remaining = len
        while (remaining > 0) {
            val bytesUntilSegmentEnd = (segmentStart + expectedSegmentBytes()) - nextOffset
            if (bytesUntilSegmentEnd <= 0L) {
                finishSegmentIfBoundaryReached()
                continue
            }
            val count = minOf(remaining.toLong(), bytesUntilSegmentEnd).toInt()
            segmentBuffer.write(buffer, sourceOffset, count)
            sourceOffset += count
            remaining -= count
            nextOffset += count.toLong()
            finishSegmentIfBoundaryReached()
        }
    }

    private fun finishSegmentIfBoundaryReached() {
        val expectedBytes = expectedSegmentBytes()
        val reachedSegmentBoundary = nextOffset >= segmentStart + expectedBytes
        if (!reachedSegmentBoundary) return
        if (segmentBuffer.size().toLong() == expectedBytes) {
            storeSegment()
        }
        advanceSegment()
    }

    private fun flushCompletedSegmentAtEndOfStream() {
        if (segmentBuffer.size() > 0 && segmentBuffer.size().toLong() == expectedSegmentBytes()) {
            storeSegment()
            advanceSegment()
        }
    }

    private fun expectedSegmentBytes(): Long =
        (segmentStart + segmentBytes).coerceAtMost(totalSize) - segmentStart

    private fun storeSegment() {
        val bytes = segmentBuffer.toByteArray()
        cache.putOwnedSegment(request.streamId, segmentIndex, segmentStart, bytes)
    }

    private fun advanceSegment() {
        segmentIndex += 1L
        segmentStart = segmentIndex * segmentBytes
        segmentBuffer = ByteArrayOutputStream()
    }
}
