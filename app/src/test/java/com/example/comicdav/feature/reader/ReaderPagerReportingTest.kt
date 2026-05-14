package com.example.comicdav.feature.reader

import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ReaderPagerReportingTest {
    @Test
    fun reportableSettledPageIsNullWhilePagerIsBetweenPages() {
        assertNull(reportableSettledPage(currentPage = 1, settledPage = 0))
        assertEquals(1, reportableSettledPage(currentPage = 1, settledPage = 1))
    }

    @Test
    fun reportableReaderPageChangesIgnoreTransientBetweenPageState() = runTest {
        val emitted = flowOf(0, null, 0, null, 1)
            .reportableReaderPageChanges()
            .toList()

        assertEquals(listOf(0, 1), emitted)
    }
}
