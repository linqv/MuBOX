package org.mubox.reader.nativebridge

import org.mubox.reader.core.ports.PlannedRemoteRange
import org.mubox.reader.core.ports.ReconciledPrefetchPlan
import org.mubox.reader.core.ports.ReconciledPrefetchTask

/** Compact metadata-only wire format used by reconcilePrefetchPlanV1. */
internal object PrefetchPlanWireV1 {
    private const val VERSION = 1L
    private const val STATUS_OK = 0L
    private const val STATUS_ERROR = 1L

    fun encodeRanges(ranges: List<PlannedRemoteRange>): LongArray = buildList {
        add(VERSION)
        add(ranges.size.toLong())
        ranges.forEach { range -> addRange(range) }
    }.toLongArray()

    fun decodePlan(values: LongArray): ReconciledPrefetchPlan? {
        val cursor = Cursor(values)
        require(cursor.nextLong("version") == VERSION) { "Unsupported reconciled prefetch plan format" }
        when (cursor.nextLong("status")) {
            STATUS_ERROR -> {
                cursor.requireFullyConsumed()
                return null
            }
            STATUS_OK -> Unit
            else -> throw IllegalArgumentException("Unsupported reconciled prefetch plan status")
        }
        val retainedPages = buildSet {
            repeat(cursor.nextCount("retained page count")) {
                add(cursor.nextInt("retained page"))
            }
        }
        val tasks = buildList {
            repeat(cursor.nextCount("task count")) {
                val range = cursor.nextRange()
                val protectedRanges = buildList {
                    repeat(cursor.nextCount("protected range count")) {
                        val start = cursor.nextNonNegativeLong("protected range start")
                        val endInclusive = cursor.nextNonNegativeLong("protected range end")
                        require(endInclusive >= start) { "Protected range end precedes start" }
                        add(start..endInclusive)
                    }
                }
                add(ReconciledPrefetchTask(range = range, protectedRanges = protectedRanges))
            }
        }
        cursor.requireFullyConsumed()
        return ReconciledPrefetchPlan(tasks = tasks, retainedPages = retainedPages)
    }

    private fun MutableList<Long>.addRange(range: PlannedRemoteRange) {
        require(range.start >= 0L) { "Range start must be non-negative" }
        require(range.endInclusive >= range.start) { "Range end precedes start" }
        require(range.priority in 0..UByte.MAX_VALUE.toInt()) { "Range priority is out of bounds" }
        require(range.pages.isNotEmpty()) { "Range pages must not be empty" }
        require(range.pages.all { it >= 0 }) { "Range page must be non-negative" }
        add(range.start)
        add(range.endInclusive)
        add(range.priority.toLong())
        add(range.pages.size.toLong())
        range.pages.forEach { page -> add(page.toLong()) }
    }

    private class Cursor(private val values: LongArray) {
        private var offset = 0

        fun nextRange(): PlannedRemoteRange {
            val start = nextNonNegativeLong("range start")
            val endInclusive = nextNonNegativeLong("range end")
            require(endInclusive >= start) { "Range end precedes start" }
            val priority = nextInt("range priority")
            require(priority in 0..UByte.MAX_VALUE.toInt()) { "Range priority is out of bounds" }
            val pages = buildList {
                repeat(nextCount("range page count")) {
                    add(nextInt("range page"))
                }
            }
            return PlannedRemoteRange(
                start = start,
                endInclusive = endInclusive,
                pages = pages,
                priority = priority,
            )
        }

        fun nextCount(label: String): Int {
            val value = nextLong(label)
            require(value in 0..Int.MAX_VALUE.toLong()) { "$label is out of bounds" }
            require(value <= values.size - offset) { "$label exceeds remaining payload" }
            return value.toInt()
        }

        fun nextInt(label: String): Int {
            val value = nextLong(label)
            require(value in 0..Int.MAX_VALUE.toLong()) { "$label is out of bounds" }
            return value.toInt()
        }

        fun nextNonNegativeLong(label: String): Long {
            val value = nextLong(label)
            require(value >= 0L) { "$label must be non-negative" }
            return value
        }

        fun nextLong(label: String): Long {
            require(offset < values.size) { "Missing $label" }
            return values[offset++]
        }

        fun requireFullyConsumed() {
            require(offset == values.size) { "Trailing reconciled prefetch plan data" }
        }
    }
}
