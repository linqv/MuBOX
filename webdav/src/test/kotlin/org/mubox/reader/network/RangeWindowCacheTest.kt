package org.mubox.reader.network

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RangeWindowCacheTest {
    @Test
    fun adjacentSegmentsAreComposedForTheRequestedRange() {
        val bytes = ByteArray(16) { it.toByte() }
        val cache = RangeWindowCache(maxBytes = 64, segmentBytes = 4)

        assertTrue(cache.store(start = 0, endInclusive = 7, bytes = bytes.sliceArray(0..7)).stored)
        assertTrue(cache.store(start = 8, endInclusive = 15, bytes = bytes.sliceArray(8..15)).stored)

        val lookup = cache.find(start = 3, endInclusive = 12)
        assertEquals(4, cache.windowCount())
        assertEquals(0L, lookup?.windowStart)
        assertEquals(15L, lookup?.windowEndInclusive)
        assertArrayEquals(bytes.sliceArray(3..12), lookup?.bytes)
    }

    @Test
    fun accessUpdatesLruOrderBeforeNextStore() {
        val cache = RangeWindowCache(maxBytes = 8, segmentBytes = 4)
        cache.store(start = 0, endInclusive = 3, bytes = byteArrayOf(0, 1, 2, 3))
        cache.store(start = 4, endInclusive = 7, bytes = byteArrayOf(4, 5, 6, 7))

        assertArrayEquals(byteArrayOf(0, 1, 2, 3), cache.find(0, 3)?.bytes)
        val store = cache.store(start = 8, endInclusive = 11, bytes = byteArrayOf(8, 9, 10, 11))

        assertTrue(store.stored)
        assertEquals(listOf(RangeWindowCache.WindowSnapshot(4, 7, 4)), store.evicted)
        assertTrue(cache.isCovered(0, 3))
        assertFalse(cache.isCovered(4, 7))
        assertTrue(cache.isCovered(8, 11))
    }

    @Test
    fun protectedRangeIsNotSelectedForEviction() {
        val cache = RangeWindowCache(maxBytes = 8, segmentBytes = 4)
        cache.store(start = 0, endInclusive = 3, bytes = byteArrayOf(0, 1, 2, 3))
        cache.store(start = 4, endInclusive = 7, bytes = byteArrayOf(4, 5, 6, 7))

        val store = cache.store(
            start = 8,
            endInclusive = 11,
            bytes = byteArrayOf(8, 9, 10, 11),
            protectedRanges = listOf(0L..3L),
        )

        assertTrue(store.stored)
        assertEquals("protected", store.evictionMode)
        assertEquals(listOf(RangeWindowCache.WindowSnapshot(4, 7, 4)), store.evicted)
        assertTrue(cache.isCovered(0, 3))
        assertFalse(cache.isCovered(4, 7))
        assertTrue(cache.isCovered(8, 11))
    }

    @Test
    fun storeIsRejectedWithoutMutatingCacheWhenProtectedRangesFillCapacity() {
        val cache = RangeWindowCache(maxBytes = 8, segmentBytes = 4)
        cache.store(start = 0, endInclusive = 3, bytes = byteArrayOf(0, 1, 2, 3))
        cache.store(start = 4, endInclusive = 7, bytes = byteArrayOf(4, 5, 6, 7))

        val store = cache.store(
            start = 8,
            endInclusive = 11,
            bytes = byteArrayOf(8, 9, 10, 11),
            protectedRanges = listOf(0L..7L),
        )

        assertFalse(store.stored)
        assertEquals("protected_capacity", store.skippedReason)
        assertEquals(8L, cache.totalBytes())
        assertTrue(cache.isCovered(0, 7))
        assertFalse(cache.isCovered(8, 11))
    }

    @Test
    fun overlappingStoreReplacesOnlyRequestedBytes() {
        val cache = RangeWindowCache(maxBytes = 16, segmentBytes = 4)
        cache.store(start = 0, endInclusive = 7, bytes = ByteArray(8) { it.toByte() })

        val replacement = byteArrayOf(20, 21, 22, 23)
        assertTrue(cache.store(start = 2, endInclusive = 5, bytes = replacement).stored)

        assertArrayEquals(
            byteArrayOf(0, 1, 20, 21, 22, 23, 6, 7),
            cache.find(0, 7)?.bytes,
        )
        assertEquals(8L, cache.totalBytes())
    }

    @Test
    fun oversizedStoreIsRejectedWithoutEvictingExistingData() {
        val cache = RangeWindowCache(maxBytes = 4, segmentBytes = 4)
        cache.store(start = 0, endInclusive = 3, bytes = byteArrayOf(0, 1, 2, 3))

        val store = cache.store(start = 4, endInclusive = 8, bytes = ByteArray(5))

        assertFalse(store.stored)
        assertEquals("oversized", store.skippedReason)
        assertEquals("none", store.evictionMode)
        assertArrayEquals(byteArrayOf(0, 1, 2, 3), cache.find(0, 3)?.bytes)
        assertNull(cache.find(4, 8))
    }
}
