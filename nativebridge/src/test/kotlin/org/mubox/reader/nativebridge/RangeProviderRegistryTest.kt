package org.mubox.reader.nativebridge

import org.mubox.reader.core.ports.RangeProvider
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RangeProviderRegistryTest {
    @Test
    fun registeringProvidersReturnsUniqueFileIdsAndRoutesReads() {
        val first = RecordingRangeProvider(size = 10, bytes = byteArrayOf(1, 2, 3))
        val second = RecordingRangeProvider(size = 20, bytes = byteArrayOf(4, 5))

        val firstId = RangeProviderRegistry.register(first)
        val secondId = RangeProviderRegistry.register(second)

        try {
            assertNotEquals(firstId, secondId)
            assertEquals(10L, RangeProviderRegistry.size(firstId))
            assertEquals(20L, RangeProviderRegistry.size(secondId))
            assertArrayEquals(byteArrayOf(1, 2, 3), RangeProviderRegistry.readRange(firstId, 2, 4))
            assertEquals(ReadCall(firstId, 2, 4), first.readCalls.single())
        } finally {
            RangeProviderRegistry.unregister(firstId)
            RangeProviderRegistry.unregister(secondId)
        }
    }

    @Test
    fun unregisterRemovesProvider() {
        val fileId = RangeProviderRegistry.register(RecordingRangeProvider(size = 5, bytes = byteArrayOf(1)))

        RangeProviderRegistry.unregister(fileId)
        val error = runCatching { RangeProviderRegistry.size(fileId) }.exceptionOrNull()

        assertTrue(error is IllegalArgumentException)
    }

    private class RecordingRangeProvider(
        private val size: Long,
        private val bytes: ByteArray,
    ) : RangeProvider {
        val readCalls = mutableListOf<ReadCall>()

        override fun size(fileId: Long): Long = size

        override fun readRange(fileId: Long, start: Long, endInclusive: Long): ByteArray {
            readCalls += ReadCall(fileId, start, endInclusive)
            return bytes
        }
    }

    private data class ReadCall(
        val fileId: Long,
        val start: Long,
        val endInclusive: Long,
    )
}
