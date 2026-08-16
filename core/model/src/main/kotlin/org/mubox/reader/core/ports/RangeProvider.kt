package org.mubox.reader.core.ports

import java.nio.ByteBuffer

interface RangeProvider {
    /**
     * Fills one exact range into native-owned memory.
     *
     * Implementations must not retain [target] after this call returns. WebDAV streams into it
     * with a fixed scratch array so a network miss does not allocate a range-sized Java array.
     */
    fun fetchRangeInto(
        fileId: Long,
        requestId: Long,
        start: Long,
        endInclusive: Long,
        target: ByteBuffer,
    ): Int

    fun cancelRangeRequest(requestId: Long) = Unit
    fun close() = Unit
}
