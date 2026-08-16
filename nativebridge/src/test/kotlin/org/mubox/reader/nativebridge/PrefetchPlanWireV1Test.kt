package org.mubox.reader.nativebridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test
import org.mubox.reader.core.ports.PlannedRemoteRange
import org.mubox.reader.core.ports.ReconciledPrefetchPlan
import org.mubox.reader.core.ports.ReconciledPrefetchTask

class PrefetchPlanWireV1Test {
    @Test
    fun rangeStateUsesVersionedCountedEncoding() {
        assertEquals(
            listOf(1L, 0L),
            PrefetchPlanWireV1.encodeRanges(emptyList()).toList(),
        )
        assertEquals(
            listOf(1L, 1L, 10L, 19L, 3L, 2L, 2L, 4L),
            PrefetchPlanWireV1.encodeRanges(
                listOf(plannedRange(10, 19, pages = listOf(2, 4), priority = 3)),
            ).toList(),
        )
    }

    @Test
    fun planDecodesTaskSpecificProtectedRanges() {
        val decoded = PrefetchPlanWireV1.decodePlan(
            longArrayOf(
                1, 0,
                2, 3, 4,
                1,
                10, 19, 3, 1, 4,
                2, 0, 9, 20, 29,
            ),
        )

        assertEquals(
            ReconciledPrefetchPlan(
                retainedPages = setOf(3, 4),
                tasks = listOf(
                    ReconciledPrefetchTask(
                        range = plannedRange(10, 19, pages = listOf(4), priority = 3),
                        protectedRanges = listOf(0L..9L, 20L..29L),
                    ),
                ),
            ),
            decoded,
        )
    }

    @Test
    fun nativeErrorIsDistinctFromSuccessfulEmptyPlan() {
        assertNull(PrefetchPlanWireV1.decodePlan(longArrayOf(1, 1)))
        assertEquals(
            ReconciledPrefetchPlan(),
            PrefetchPlanWireV1.decodePlan(longArrayOf(1, 0, 0, 0)),
        )
    }

    @Test
    fun malformedOrTrailingPlanDataIsRejected() {
        listOf(
            longArrayOf(),
            longArrayOf(2, 0, 0, 0),
            longArrayOf(1, 2),
            longArrayOf(1, 0, 0),
            longArrayOf(1, 0, 0, 0, 99),
            longArrayOf(1, 0, 0, 1, 10, 9, 0, 1, 0, 0),
        ).forEach { values ->
            assertThrows(IllegalArgumentException::class.java) {
                PrefetchPlanWireV1.decodePlan(values)
            }
        }
    }

    @Test
    fun invalidStateRangeIsRejectedBeforeJni() {
        assertThrows(IllegalArgumentException::class.java) {
            PrefetchPlanWireV1.encodeRanges(
                listOf(plannedRange(10, 9, pages = listOf(0), priority = 0)),
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            PrefetchPlanWireV1.encodeRanges(
                listOf(plannedRange(10, 19, pages = emptyList(), priority = 0)),
            )
        }
    }

    private fun plannedRange(
        start: Long,
        endInclusive: Long,
        pages: List<Int>,
        priority: Int,
    ): PlannedRemoteRange = PlannedRemoteRange(
        start = start,
        endInclusive = endInclusive,
        pages = pages,
        priority = priority,
    )
}
