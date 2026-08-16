package org.mubox.reader.nativebridge

internal object RangeIoWireV1 {
    private const val VERSION = 1L

    fun encodeProtectedRanges(ranges: List<LongRange>): LongArray = buildList {
        add(VERSION)
        add(ranges.size.toLong())
        ranges.forEach { range ->
            require(!range.isEmpty()) { "Protected range must not be empty" }
            require(range.first >= 0L) { "Protected range start must be non-negative" }
            add(range.first)
            add(range.last)
        }
    }.toLongArray()
}
