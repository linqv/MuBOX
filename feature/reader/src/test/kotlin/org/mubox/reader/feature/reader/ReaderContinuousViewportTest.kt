package org.mubox.reader.feature.reader

import org.junit.Assert.assertEquals
import org.junit.Test

class ReaderContinuousViewportTest {
    @Test
    fun directionFollowsPageIndexAndOffset() {
        val previous = ReaderContinuousViewportPosition(firstVisiblePage = 10, firstVisibleOffset = 100)

        assertEquals(1, readerContinuousScrollDirection(previous, ReaderContinuousViewportPosition(11, 0), true))
        assertEquals(-1, readerContinuousScrollDirection(previous, ReaderContinuousViewportPosition(9, 500), true))
        assertEquals(1, readerContinuousScrollDirection(previous, ReaderContinuousViewportPosition(10, 101), true))
        assertEquals(-1, readerContinuousScrollDirection(previous, ReaderContinuousViewportPosition(10, 99), true))
    }

    @Test
    fun layoutChangesOutsideScrollDoNotChangeDirection() {
        val previous = ReaderContinuousViewportPosition(firstVisiblePage = 10, firstVisibleOffset = 100)

        assertEquals(0, readerContinuousScrollDirection(previous, ReaderContinuousViewportPosition(10, 500), false))
        assertEquals(0, readerContinuousScrollDirection(null, previous, true))
    }
}
