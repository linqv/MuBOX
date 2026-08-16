package org.mubox.reader.nativebridge

import java.nio.ByteBuffer
import org.mubox.reader.core.ports.RangeProvider
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class RangeProviderRegistryTest {
    @Test
    fun registeringProvidersReturnsUniqueIds() {
        val first = RecordingRangeProvider(byteArrayOf(1, 2, 3))
        val second = RecordingRangeProvider(byteArrayOf(4, 5))
        val firstId = RangeProviderRegistry.register(first)
        val secondId = RangeProviderRegistry.register(second)

        try {
            assertNotEquals(firstId, secondId)
        } finally {
            RangeProviderRegistry.unregister(firstId)
            RangeProviderRegistry.unregister(secondId)
        }
    }

    @Test
    fun v1CallbackFillsDirectBufferAndPreservesRequestIdentity() {
        val provider = RecordingRangeProvider(byteArrayOf(10, 11, 12, 13))
        val fileId = RangeProviderRegistry.register(provider)
        val target = ByteBuffer.allocateDirect(4)

        try {
            val written = RangeProviderRegistry.fetchRangeIntoV1(
                fileId = fileId,
                requestId = 91,
                start = 20,
                endInclusive = 23,
                target = target,
            )

            assertEquals(4, written)
            assertEquals(4, target.position())
            target.flip()
            assertArrayEquals(byteArrayOf(10, 11, 12, 13), ByteArray(4).also(target::get))
            assertEquals(FetchCall(fileId, 91, 20, 23), provider.fetchCalls.single())
        } finally {
            RangeProviderRegistry.unregister(fileId)
        }
    }

    @Test
    fun v1CallbackRejectsNonCanonicalNativeBuffersAndInvalidRanges() {
        val provider = RecordingRangeProvider(ByteArray(10))
        val fileId = RangeProviderRegistry.register(provider)

        try {
            assertThrows(IllegalArgumentException::class.java) {
                RangeProviderRegistry.fetchRangeIntoV1(fileId, 0, 10, 19, ByteBuffer.allocateDirect(10))
            }
            assertThrows(IllegalArgumentException::class.java) {
                RangeProviderRegistry.fetchRangeIntoV1(fileId, 1, -1, 8, ByteBuffer.allocateDirect(10))
            }
            assertThrows(IllegalArgumentException::class.java) {
                RangeProviderRegistry.fetchRangeIntoV1(fileId, 1, 20, 19, ByteBuffer.allocateDirect(0))
            }
            assertThrows(IllegalArgumentException::class.java) {
                RangeProviderRegistry.fetchRangeIntoV1(fileId, 1, 0, Long.MAX_VALUE, ByteBuffer.allocateDirect(1))
            }
            assertThrows(IllegalArgumentException::class.java) {
                RangeProviderRegistry.fetchRangeIntoV1(fileId, 1, 10, 19, ByteBuffer.allocate(10))
            }
            assertThrows(IllegalArgumentException::class.java) {
                RangeProviderRegistry.fetchRangeIntoV1(
                    fileId,
                    1,
                    10,
                    19,
                    ByteBuffer.allocateDirect(10).asReadOnlyBuffer(),
                )
            }
            assertThrows(IllegalArgumentException::class.java) {
                RangeProviderRegistry.fetchRangeIntoV1(fileId, 1, 10, 19, ByteBuffer.allocateDirect(9))
            }
            val offsetTarget = ByteBuffer.allocateDirect(12).apply {
                position(1)
                limit(11)
            }
            assertThrows(IllegalArgumentException::class.java) {
                RangeProviderRegistry.fetchRangeIntoV1(fileId, 1, 10, 19, offsetTarget)
            }
            val wrongSizedSlice = ByteBuffer.allocateDirect(12).apply { position(1) }.slice()
            assertThrows(IllegalArgumentException::class.java) {
                RangeProviderRegistry.fetchRangeIntoV1(fileId, 1, 10, 19, wrongSizedSlice)
            }

            assertTrue(provider.fetchCalls.isEmpty())
        } finally {
            RangeProviderRegistry.unregister(fileId)
        }
    }

    @Test
    fun v1CallbackRejectsProviderThatMisreportsOrPartiallyFillsResponse() {
        val wrongCount = RecordingRangeProvider(
            bytes = byteArrayOf(1, 2, 3, 4),
            reportedWritten = 3,
        )
        val wrongCountId = RangeProviderRegistry.register(wrongCount)
        try {
            assertThrows(IllegalArgumentException::class.java) {
                RangeProviderRegistry.fetchRangeIntoV1(
                    wrongCountId,
                    1,
                    0,
                    3,
                    ByteBuffer.allocateDirect(4),
                )
            }
        } finally {
            RangeProviderRegistry.unregister(wrongCountId)
        }

        val partialFill = RecordingRangeProvider(
            bytes = byteArrayOf(1, 2, 3),
            reportedWritten = 4,
        )
        val partialFillId = RangeProviderRegistry.register(partialFill)
        try {
            assertThrows(IllegalArgumentException::class.java) {
                RangeProviderRegistry.fetchRangeIntoV1(
                    partialFillId,
                    2,
                    0,
                    3,
                    ByteBuffer.allocateDirect(4),
                )
            }
        } finally {
            RangeProviderRegistry.unregister(partialFillId)
        }
    }

    @Test
    fun v1CancelCallbackRoutesByFileAndRequestId() {
        val provider = RecordingRangeProvider(ByteArray(1))
        val fileId = RangeProviderRegistry.register(provider)

        try {
            RangeProviderRegistry.cancelRangeFetchV1(fileId, 77)

            assertEquals(listOf(77L), provider.cancelledRequestIds)
            RangeProviderRegistry.cancelRangeFetchV1(Long.MAX_VALUE, 78)
            assertEquals(listOf(77L), provider.cancelledRequestIds)
            assertThrows(IllegalArgumentException::class.java) {
                RangeProviderRegistry.cancelRangeFetchV1(fileId, 0)
            }
        } finally {
            RangeProviderRegistry.unregister(fileId)
        }
    }

    @Test
    fun unregisterClosesAndRemovesProvider() {
        val provider = RecordingRangeProvider(byteArrayOf(1))
        val fileId = RangeProviderRegistry.register(provider)

        RangeProviderRegistry.unregister(fileId)

        assertEquals(1, provider.closeCalls)
        assertThrows(IllegalArgumentException::class.java) {
            RangeProviderRegistry.fetchRangeIntoV1(
                fileId,
                1,
                0,
                0,
                ByteBuffer.allocateDirect(1),
            )
        }
        RangeProviderRegistry.cancelRangeFetchV1(fileId, 1)
        assertTrue(provider.cancelledRequestIds.isEmpty())
    }

    private class RecordingRangeProvider(
        private val bytes: ByteArray,
        private val reportedWritten: Int = bytes.size,
    ) : RangeProvider {
        val fetchCalls = mutableListOf<FetchCall>()
        val cancelledRequestIds = mutableListOf<Long>()
        var closeCalls = 0

        override fun fetchRangeInto(
            fileId: Long,
            requestId: Long,
            start: Long,
            endInclusive: Long,
            target: ByteBuffer,
        ): Int {
            fetchCalls += FetchCall(fileId, requestId, start, endInclusive)
            target.put(bytes)
            return reportedWritten
        }

        override fun cancelRangeRequest(requestId: Long) {
            cancelledRequestIds += requestId
        }

        override fun close() {
            closeCalls += 1
        }
    }

    private data class FetchCall(
        val fileId: Long,
        val requestId: Long,
        val start: Long,
        val endInclusive: Long,
    )
}
