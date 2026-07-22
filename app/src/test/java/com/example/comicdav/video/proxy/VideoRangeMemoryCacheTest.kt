package com.example.comicdav.video.proxy

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class VideoRangeMemoryCacheTest {
    @Test
    fun defaultSizingUsesTwoMiBSegmentsAndSixtyFourMiBCapacity() {
        assertEquals(2L * 1024L * 1024L, VideoRangeMemoryCache.DEFAULT_SEGMENT_BYTES)
        assertEquals(64L * 1024L * 1024L, VideoRangeMemoryCache.DEFAULT_MAX_BYTES)
    }

    @Test
    fun getSegmentReturnsStoredBytesAndUpdatesByteStats() {
        val cache = VideoRangeMemoryCache(maxBytes = 8)
        cache.putSegment("stream-1", segmentIndex = 0L, start = 0L, bytes = "abcd".toByteArray())

        val segment = cache.getSegment("stream-1", segmentIndex = 0L)

        assertEquals("stream-1", segment?.streamId)
        assertEquals(0L, segment?.segmentIndex)
        assertEquals(0L, segment?.start)
        assertEquals(3L, segment?.endInclusive)
        assertArrayEquals("abcd".toByteArray(), segment?.bytes)
        assertEquals(4L, cache.totalBytes())
        assertEquals(1, cache.segmentCount())
    }

    @Test
    fun getSegmentKeepsDefensiveSnapshotButInternalReferenceAvoidsCopy() {
        val cache = VideoRangeMemoryCache()
        val bytes = byteArrayOf(1, 2, 3)

        cache.putSegment(streamId = "stream", segmentIndex = 0L, start = 0L, bytes = bytes)

        val snapshot = cache.getSegment("stream", 0L)
        val reference = cache.getSegmentReference("stream", 0L)

        assertNotSame(snapshot, reference)
        assertArrayEquals(byteArrayOf(1, 2, 3), snapshot!!.bytes)
        assertArrayEquals(byteArrayOf(1, 2, 3), reference!!.bytes)
        assertSame(reference, cache.getSegmentReference("stream", 0L))
    }

    @Test
    fun putOwnedSegmentStoresExclusiveInternalBytesWithoutCopying() {
        val cache = VideoRangeMemoryCache()
        val bytes = byteArrayOf(1, 2, 3)

        cache.putOwnedSegment(streamId = "stream", segmentIndex = 0L, start = 0L, bytes = bytes)

        val reference = cache.getSegmentReference("stream", 0L)
        assertSame(bytes, reference!!.bytes)
        assertArrayEquals(byteArrayOf(1, 2, 3), cache.getSegment("stream", 0L)?.bytes)
    }

    @Test
    fun putSegmentEvictsLeastRecentlyUsedSegmentsByByteCapacity() {
        val cache = VideoRangeMemoryCache(maxBytes = 8)
        cache.putSegment("stream-1", 0L, 0L, "aaaa".toByteArray())
        cache.putSegment("stream-1", 1L, 4L, "bbbb".toByteArray())
        cache.getSegment("stream-1", 0L)

        cache.putSegment("stream-1", 2L, 8L, "cccc".toByteArray())

        assertArrayEquals("aaaa".toByteArray(), cache.getSegment("stream-1", 0L)?.bytes)
        assertNull(cache.getSegment("stream-1", 1L))
        assertArrayEquals("cccc".toByteArray(), cache.getSegment("stream-1", 2L)?.bytes)
        assertEquals(8L, cache.totalBytes())
    }

    @Test
    fun getSegmentSliceCopiesOnlyRequestedRangeAndUpdatesRecency() {
        val cache = VideoRangeMemoryCache(maxBytes = 8)
        cache.putSegment("stream-1", 0L, 0L, "abcd".toByteArray())
        cache.putSegment("stream-1", 1L, 4L, "efgh".toByteArray())

        val slice = cache.getSegmentSlice("stream-1", 0L, 1L, 2L)
        cache.putSegment("stream-1", 2L, 8L, "ijkl".toByteArray())

        assertArrayEquals("bc".toByteArray(), slice)
        assertArrayEquals("abcd".toByteArray(), cache.getSegment("stream-1", 0L)?.bytes)
        assertNull(cache.getSegment("stream-1", 1L))
        assertArrayEquals("ijkl".toByteArray(), cache.getSegment("stream-1", 2L)?.bytes)
    }

    @Test
    fun internalSliceReferenceReusesImmutableCachedBytes() {
        val cache = VideoRangeMemoryCache()
        val bytes = "abcdef".toByteArray()
        cache.putOwnedSegment("stream-1", 0L, 0L, bytes)

        val slice = cache.getSegmentSliceReference("stream-1", 0L, 2L, 4L)!!

        assertSame(bytes, slice.bytes)
        assertEquals(2, slice.fromIndex)
        assertEquals(5, slice.toIndexExclusive)
        assertEquals(3, slice.size)
    }

    @Test
    fun streamScopedKeysKeepSameSegmentIndexIsolated() {
        val cache = VideoRangeMemoryCache(maxBytes = 16)
        cache.putSegment("stream-1", 0L, 0L, "aaaa".toByteArray())
        cache.putSegment("stream-2", 0L, 0L, "bbbb".toByteArray())

        assertArrayEquals("aaaa".toByteArray(), cache.getSegment("stream-1", 0L)?.bytes)
        assertArrayEquals("bbbb".toByteArray(), cache.getSegment("stream-2", 0L)?.bytes)
    }

    @Test
    fun removeStreamClearsOnlyThatStreamsSegments() {
        val cache = VideoRangeMemoryCache(maxBytes = 16)
        cache.putSegment("stream-1", 0L, 0L, "aaaa".toByteArray())
        cache.putSegment("stream-1", 1L, 4L, "cccc".toByteArray())
        cache.putSegment("stream-2", 0L, 0L, "bbbb".toByteArray())

        cache.removeStream("stream-1")

        assertNull(cache.getSegment("stream-1", 0L))
        assertNull(cache.getSegment("stream-1", 1L))
        assertArrayEquals("bbbb".toByteArray(), cache.getSegment("stream-2", 0L)?.bytes)
        assertEquals(4L, cache.totalBytes())
    }

    @Test
    fun oversizedSegmentIsRejected() {
        val cache = VideoRangeMemoryCache(maxBytes = 3)

        val stored = cache.putSegment("stream-1", 0L, 0L, "abcd".toByteArray())

        assertFalse(stored)
        assertNull(cache.getSegment("stream-1", 0L))
        assertEquals(0L, cache.totalBytes())
    }

    @Test
    fun clearDropsAllSegments() {
        val cache = VideoRangeMemoryCache(maxBytes = 16)
        cache.putSegment("stream-1", 0L, 0L, "aaaa".toByteArray())
        cache.putSegment("stream-2", 0L, 0L, "bbbb".toByteArray())

        cache.clear()

        assertNull(cache.getSegment("stream-1", 0L))
        assertNull(cache.getSegment("stream-2", 0L))
        assertEquals(0L, cache.totalBytes())
        assertEquals(0, cache.segmentCount())
    }
}
