package org.mubox.reader.nativebridge

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class RangeIoWireV1Test {
    @Test
    fun emptyProtectionEncodesVersionAndZeroCount() {
        assertArrayEquals(
            longArrayOf(1, 0),
            RangeIoWireV1.encodeProtectedRanges(emptyList()),
        )
    }

    @Test
    fun protectionPreservesCallerOrderAndInclusiveEndpoints() {
        assertArrayEquals(
            longArrayOf(1, 3, 20, 29, 0, 0, 10, 19),
            RangeIoWireV1.encodeProtectedRanges(
                listOf(20L..29L, 0L..0L, 10L..19L),
            ),
        )
    }

    @Test
    fun invalidProtectionIsRejectedBeforeNativeCall() {
        assertThrows(IllegalArgumentException::class.java) {
            RangeIoWireV1.encodeProtectedRanges(listOf(10L..9L))
        }
        assertThrows(IllegalArgumentException::class.java) {
            RangeIoWireV1.encodeProtectedRanges(listOf(-1L..9L))
        }
    }
}
