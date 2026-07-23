package com.example.comicdav.feature.reader

import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotEquals
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

    @Test
    fun reportablePagerDemandPagesIncludesCurrentAndTargetOnce() {
        val snapshot = ReaderPagerSnapshot(
            currentPage = 2,
            settledPage = 2,
            targetPage = 3,
            offsetFraction = 0.2f,
            isScrollInProgress = true,
            uiCurrentPage = 2,
            pageCount = 10,
        )

        assertEquals(
            listOf(
                ReaderPageDemand(page = 2, source = "pager_current"),
                ReaderPageDemand(page = 3, source = "pager_target"),
            ),
            reportablePagerDemandPages(snapshot),
        )
    }

    @Test
    fun reportablePagerDemandPagesDoesNotDuplicateCurrentPage() {
        val snapshot = ReaderPagerSnapshot(
            currentPage = 2,
            settledPage = 2,
            targetPage = 2,
            offsetFraction = 0f,
            isScrollInProgress = false,
            uiCurrentPage = 2,
            pageCount = 10,
        )

        assertEquals(
            listOf(ReaderPageDemand(page = 2, source = "pager_current")),
            reportablePagerDemandPages(snapshot),
        )
    }

    @Test
    fun readerScrollStateKeyChangesBetweenBooksWithSameInitialPage() {
        val first = ReaderUiState(pageCount = 20, currentPage = 0, readerKey = "first")
        val second = ReaderUiState(pageCount = 20, currentPage = 0, readerKey = "second")

        assertNotEquals(readerScrollStateKey(first), readerScrollStateKey(second))
    }

    @Test
    fun continuousPageChangeIgnoresIdleLayoutDrivenChanges() {
        assertNull(
            reportableContinuousPageChange(
                firstVisiblePage = 5,
                isScrollInProgress = false,
                pageCount = 10,
            ),
        )
    }

    @Test
    fun continuousPageChangeReportsUserDrivenScrollChanges() {
        assertEquals(
            5,
            reportableContinuousPageChange(
                firstVisiblePage = 5,
                isScrollInProgress = true,
                pageCount = 10,
            ),
        )
    }
}
