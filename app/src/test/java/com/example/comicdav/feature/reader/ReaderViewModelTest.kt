package com.example.comicdav.feature.reader

import com.example.comicdav.data.ReaderLoggingMode
import com.example.comicdav.nativebridge.ComicReaderSession
import com.example.comicdav.nativebridge.PlannedRemoteRange
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.rules.TemporaryFolder

@OptIn(ExperimentalCoroutinesApi::class)
class ReaderViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val temp = TemporaryFolder().apply { create() }

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        ReaderDiagnosticLog.clearSink()
        ReaderDiagnosticLog.setMode(ReaderLoggingMode.SUMMARY)
        Dispatchers.resetMain()
    }

    @Test
    fun openLocalLoadsCurrentPageAndPrefetchesForwardWindow() = runTest(dispatcher) {
        val session = FakeReaderSession(pageCount = 4)
        val viewModel = ReaderViewModel(
            openSession = { session },
            ioDispatcher = dispatcher,
        )

        viewModel.openLocal("/tmp/book.cbz", temp.root)
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(4, viewModel.uiState.pageCount)
        assertEquals(0, viewModel.uiState.currentPage)
        assertEquals(listOf(0, 1, 2, 3), session.loadedPages)
        assertEquals(setOf(0, 1, 2, 3), viewModel.uiState.pageFiles.keys)
    }

    @Test
    fun prefetchPublishesEarlierPagesWhenLaterForwardPageFails() = runTest(dispatcher) {
        val session = FakeReaderSession(pageCount = 4, failOnPages = setOf(2))
        val viewModel = ReaderViewModel(
            openSession = { session },
            ioDispatcher = dispatcher,
        )

        viewModel.openLocal("/tmp/book.cbz", temp.root)
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(0, viewModel.uiState.currentPage)
        assertTrue(session.loadedPages.containsAll(listOf(0, 1, 2)))
        assertTrue(viewModel.uiState.pageFiles.keys.contains(1))
        assertTrue(!viewModel.uiState.pageFiles.keys.contains(2))
        assertTrue(!viewModel.uiState.pageFiles.keys.contains(3))
    }

    @Test
    fun selectPageLoadsPreviousCurrentAndNextPage() = runTest(dispatcher) {
        val session = FakeReaderSession(pageCount = 5)
        val viewModel = ReaderViewModel(
            openSession = { session },
            ioDispatcher = dispatcher,
        )
        viewModel.openLocal("/tmp/book.cbz", temp.root)
        dispatcher.scheduler.advanceUntilIdle()

        viewModel.selectPage(2)
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(2, viewModel.uiState.currentPage)
        assertTrue(session.loadedPages.containsAll(listOf(1, 2, 3)))
        assertTrue(viewModel.uiState.pageFiles.keys.containsAll(setOf(1, 2, 3)))
    }

    @Test
    fun selectPageLoadsCurrentPageBeforeNeighbors() = runTest(dispatcher) {
        val session = FakeReaderSession(pageCount = 7)
        val viewModel = ReaderViewModel(
            openSession = { session },
            ioDispatcher = dispatcher,
        )
        viewModel.openLocal("/tmp/book.cbz", temp.root)
        dispatcher.scheduler.advanceUntilIdle()
        session.loadedPages.clear()

        viewModel.selectPage(5)
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(5, session.loadedPages.first())
    }

    @Test
    fun openAndSelectPageUpdateNativeViewport() = runTest(dispatcher) {
        val session = FakeReaderSession(pageCount = 5)
        val viewModel = ReaderViewModel(
            openSession = { session },
            ioDispatcher = dispatcher,
        )

        viewModel.openLocal("/tmp/book.cbz", temp.root)
        dispatcher.scheduler.advanceUntilIdle()
        viewModel.selectPage(2)
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(listOf(0, 2), session.viewportPages)
    }

    @Test
    fun viewportPlanRequestsArePrefetchedInBackground() = runTest(dispatcher) {
        val session = FakeReaderSession(
            pageCount = 5,
            plannedRangesByPage = mapOf(
                0 to listOf(
                    PlannedRemoteRange(start = 100, endInclusive = 199, pages = listOf(1, 2), priority = 1),
                ),
            ),
        )
        val viewModel = ReaderViewModel(
            openSession = { session },
            ioDispatcher = dispatcher,
        )

        viewModel.openLocal("/tmp/book.cbz", temp.root)
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(listOf(100L to 199L), session.prefetchedRanges)
    }

    @Test
    fun plannedRangePrefetchPassesPriorityToSession() = runTest(dispatcher) {
        val session = FakeReaderSession(
            pageCount = 5,
            plannedRangesByPage = mapOf(
                0 to listOf(
                    PlannedRemoteRange(start = 100, endInclusive = 199, pages = listOf(1), priority = 4),
                ),
            ),
        )
        val viewModel = ReaderViewModel(
            openSession = { session },
            ioDispatcher = dispatcher,
        )

        viewModel.openLocal("/tmp/book.cbz", temp.root)
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(
            listOf(RangePrefetchCall(start = 100, endInclusive = 199, priority = 4, protectedRanges = emptyList())),
            session.prefetchCalls,
        )
    }

    @Test
    fun lowPriorityPlannedRangePrefetchReceivesHighPriorityProtectedRangesFromSamePlan() = runTest(dispatcher) {
        val session = FakeReaderSession(
            pageCount = 5,
            plannedRangesByPage = mapOf(
                0 to listOf(
                    PlannedRemoteRange(start = 100, endInclusive = 199, pages = listOf(1), priority = 0),
                    PlannedRemoteRange(start = 300, endInclusive = 349, pages = listOf(2), priority = 2),
                    PlannedRemoteRange(start = 500, endInclusive = 599, pages = listOf(3), priority = 5),
                ),
            ),
        )
        val viewModel = ReaderViewModel(
            openSession = { session },
            ioDispatcher = dispatcher,
        )

        viewModel.openLocal("/tmp/book.cbz", temp.root)
        dispatcher.scheduler.advanceUntilIdle()

        val lowPriorityCall = session.prefetchCalls.single { it.start == 500L }
        assertEquals(
            listOf(100L..199L, 300L..349L),
            lowPriorityCall.protectedRanges,
        )
    }

    @Test
    fun sameStartPlannedRangesAreMergedBeforeScheduling() = runTest(dispatcher) {
        val sink = CollectingReaderLogSink()
        ReaderDiagnosticLog.setSink(sink)
        ReaderDiagnosticLog.setMode(ReaderLoggingMode.DETAIL)
        try {
            val session = FakeReaderSession(
                pageCount = 5,
                plannedRangesByPage = mapOf(
                    0 to listOf(
                        PlannedRemoteRange(start = 100, endInclusive = 199, pages = listOf(1, 2), priority = 1),
                        PlannedRemoteRange(start = 100, endInclusive = 299, pages = listOf(2, 3), priority = 4),
                    ),
                ),
            )
            val viewModel = ReaderViewModel(
                openSession = { session },
                ioDispatcher = dispatcher,
            )

            viewModel.openLocal("/tmp/book.cbz", temp.root)
            dispatcher.scheduler.advanceUntilIdle()

            assertEquals(
                listOf(RangePrefetchCall(start = 100, endInclusive = 299, priority = 1, protectedRanges = emptyList())),
                session.prefetchCalls,
            )
            assertTrue(
                sink.lines.any {
                    it.contains("planned_range_prefetch_start start=100 end=299 pages=[1, 2, 3] priority=1")
                },
            )
        } finally {
            ReaderDiagnosticLog.setMode(ReaderLoggingMode.SUMMARY)
        }
    }

    @Test
    fun summaryModeSuppressesPlannedRangePrefetchNoise() = runTest(dispatcher) {
        val sink = CollectingReaderLogSink()
        ReaderDiagnosticLog.setSink(sink)
        ReaderDiagnosticLog.setMode(ReaderLoggingMode.SUMMARY)
        try {
            val session = FakeReaderSession(
                pageCount = 5,
                plannedRangesByPage = mapOf(
                    0 to listOf(
                        PlannedRemoteRange(start = 100, endInclusive = 199, pages = listOf(1, 2), priority = 1),
                    ),
                ),
            )
            val viewModel = ReaderViewModel(
                openSession = { session },
                ioDispatcher = dispatcher,
            )

            viewModel.openLocal("/tmp/book.cbz", temp.root)
            dispatcher.scheduler.advanceUntilIdle()

            assertTrue(
                sink.lines.none { it.contains("planned_range_prefetch_start start=100 end=199") },
            )
        } finally {
            ReaderDiagnosticLog.setMode(ReaderLoggingMode.SUMMARY)
        }
    }

    @Test
    fun protectedPlannedRangesAreCappedByBudget() = runTest(dispatcher) {
        val twentyMiB = 20L * 1024L * 1024L
        val completedStart = 0L
        val currentHighStart = 50_000_000L
        val currentLowStart = 100_000_000L
        val session = FakeReaderSession(
            pageCount = 20,
            plannedRangesByPage = mapOf(
                9 to listOf(
                    PlannedRemoteRange(
                        start = completedStart,
                        endInclusive = completedStart + twentyMiB - 1,
                        pages = listOf(9),
                        priority = 0,
                    ),
                ),
                5 to listOf(
                    PlannedRemoteRange(
                        start = currentHighStart,
                        endInclusive = currentHighStart + twentyMiB - 1,
                        pages = listOf(4),
                        priority = 0,
                    ),
                    PlannedRemoteRange(
                        start = currentLowStart,
                        endInclusive = currentLowStart + twentyMiB - 1,
                        pages = listOf(6),
                        priority = 5,
                    ),
                ),
            ),
        )
        val viewModel = ReaderViewModel(
            openSession = { session },
            ioDispatcher = dispatcher,
        )
        viewModel.openLocal("/tmp/book.cbz", temp.root)
        dispatcher.scheduler.advanceUntilIdle()

        viewModel.reportPageDemand(9, "pager_target")
        dispatcher.scheduler.advanceUntilIdle()
        session.prefetchCalls.clear()

        viewModel.reportPageDemand(5, "pager_target")
        dispatcher.scheduler.advanceUntilIdle()

        val lowPriorityCall = session.prefetchCalls.single { it.start == currentLowStart }
        assertTrue(
            "protectedRanges=${lowPriorityCall.protectedRanges}",
            lowPriorityCall.protectedRanges.sumOf { it.last - it.first + 1 } <= 32L * 1024L * 1024L,
        )
        assertEquals(
            listOf(currentHighStart..(currentHighStart + twentyMiB - 1)),
            lowPriorityCall.protectedRanges,
        )
    }

    @Test
    fun protectedPlannedRangeBudgetSkipsOversizedCandidateAndKeepsSmallerCandidate() = runTest(dispatcher) {
        val oversizedBytes = 40L * 1024L * 1024L
        val smallBytes = 1L * 1024L * 1024L
        val oversizedStart = 0L
        val smallStart = 50_000_000L
        val lowPriorityStart = 100_000_000L
        val session = FakeReaderSession(
            pageCount = 12,
            plannedRangesByPage = mapOf(
                5 to listOf(
                    PlannedRemoteRange(
                        start = oversizedStart,
                        endInclusive = oversizedStart + oversizedBytes - 1,
                        pages = listOf(4),
                        priority = 0,
                    ),
                    PlannedRemoteRange(
                        start = smallStart,
                        endInclusive = smallStart + smallBytes - 1,
                        pages = listOf(6),
                        priority = 1,
                    ),
                    PlannedRemoteRange(
                        start = lowPriorityStart,
                        endInclusive = lowPriorityStart + smallBytes - 1,
                        pages = listOf(8),
                        priority = 5,
                    ),
                ),
            ),
        )
        val viewModel = ReaderViewModel(
            openSession = { session },
            ioDispatcher = dispatcher,
        )

        viewModel.openLocal("/tmp/book.cbz", temp.root)
        dispatcher.scheduler.advanceUntilIdle()
        session.prefetchCalls.clear()

        viewModel.reportPageDemand(5, "pager_target")
        dispatcher.scheduler.advanceUntilIdle()

        val lowPriorityCall = session.prefetchCalls.single { it.start == lowPriorityStart }
        assertEquals(
            listOf(smallStart..(smallStart + smallBytes - 1)),
            lowPriorityCall.protectedRanges,
        )
    }

    @Test
    fun lowPriorityPlannedRangesAreSerialized() = runTest(dispatcher) {
        val executor = java.util.concurrent.Executors.newFixedThreadPool(4)
        val ioDispatcher = executor.asCoroutineDispatcher()
        val firstLowPriorityStarted = CountDownLatch(1)
        val releaseLowPriority = CountDownLatch(1)
        val session = ConcurrencyTrackingPlannedRangeSession(
            pageCount = 8,
            plannedRangesByPage = mapOf(
                0 to listOf(
                    PlannedRemoteRange(start = 100, endInclusive = 199, pages = listOf(1), priority = 5),
                    PlannedRemoteRange(start = 200, endInclusive = 299, pages = listOf(2), priority = 6),
                    PlannedRemoteRange(start = 300, endInclusive = 399, pages = listOf(3), priority = 7),
                ),
            ),
            blockingRange = 100L to 199L,
            firstBlockedRangeStarted = firstLowPriorityStarted,
            releaseBlockedRange = releaseLowPriority,
            blockingRanges = setOf(100L to 199L, 200L to 299L, 300L to 399L),
        )
        val viewModel = ReaderViewModel(
            openSession = { session },
            ioDispatcher = ioDispatcher,
        )
        try {
            viewModel.openLocal("/tmp/book.cbz", temp.root)
            waitUntil(timeoutMs = 1_000) {
                dispatcher.scheduler.advanceUntilIdle()
                firstLowPriorityStarted.count == 0L
            }

            Thread.sleep(150)
            assertEquals(1, session.maxConcurrentPrefetches)
            assertEquals(1, session.prefetchedRanges.size)

            releaseLowPriority.countDown()
            waitUntil(timeoutMs = 1_000) {
                dispatcher.scheduler.advanceUntilIdle()
                session.prefetchedRanges.containsAll(
                    listOf(100L to 199L, 200L to 299L, 300L to 399L),
                )
            }

            assertEquals(1, session.maxConcurrentPrefetches)
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun plannedRangePrefetchesRespectTotalConcurrencyLimit() = runTest(dispatcher) {
        val executor = java.util.concurrent.Executors.newFixedThreadPool(4)
        val ioDispatcher = executor.asCoroutineDispatcher()
        val firstStarted = CountDownLatch(1)
        val releaseFirst = CountDownLatch(1)
        val session = ConcurrencyTrackingPlannedRangeSession(
            pageCount = 8,
            plannedRangesByPage = mapOf(
                0 to listOf(
                    PlannedRemoteRange(start = 100, endInclusive = 199, pages = listOf(1), priority = 0),
                    PlannedRemoteRange(start = 200, endInclusive = 299, pages = listOf(2), priority = 1),
                    PlannedRemoteRange(start = 300, endInclusive = 399, pages = listOf(3), priority = 2),
                ),
            ),
            blockingRange = 100L to 199L,
            firstBlockedRangeStarted = firstStarted,
            releaseBlockedRange = releaseFirst,
            blockingRanges = setOf(100L to 199L, 200L to 299L, 300L to 399L),
        )
        val viewModel = ReaderViewModel(
            openSession = { session },
            ioDispatcher = ioDispatcher,
        )
        try {
            viewModel.openLocal("/tmp/book.cbz", temp.root)
            waitUntil(timeoutMs = 1_000) {
                dispatcher.scheduler.advanceUntilIdle()
                firstStarted.count == 0L && session.maxConcurrentPrefetches == 2
            }

            Thread.sleep(150)
            assertEquals(2, session.maxConcurrentPrefetches)
            assertEquals(2, session.prefetchedRanges.size)

            releaseFirst.countDown()
            waitUntil(timeoutMs = 1_000) {
                dispatcher.scheduler.advanceUntilIdle()
                session.prefetchedRanges.contains(300L to 399L)
            }
            assertEquals(2, session.maxConcurrentPrefetches)
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun pagerTargetDemandRequestsPlannedRangePrefetchBeforeSelectionSettles() = runTest(dispatcher) {
        val session = FakeReaderSession(
            pageCount = 8,
            plannedRangesByPage = mapOf(
                5 to listOf(
                    PlannedRemoteRange(start = 500, endInclusive = 599, pages = listOf(5, 6), priority = 1),
                ),
            ),
        )
        val viewModel = ReaderViewModel(
            openSession = { session },
            ioDispatcher = dispatcher,
        )
        viewModel.openLocal("/tmp/book.cbz", temp.root)
        dispatcher.scheduler.advanceUntilIdle()
        session.plannedRangePages.clear()
        session.prefetchedRanges.clear()

        viewModel.reportPageDemand(5, "pager_target")
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(listOf(5), session.plannedRangePages)
        assertEquals(listOf(500L to 599L), session.prefetchedRanges)
    }

    @Test
    fun repeatedIdenticalCompletedPlannedRangePlanDoesNotPrefetchAgainInSameGeneration() = runTest(dispatcher) {
        val session = FakeReaderSession(
            pageCount = 8,
            plannedRangesByPage = mapOf(
                5 to listOf(
                    PlannedRemoteRange(start = 500, endInclusive = 599, pages = listOf(5), priority = 1),
                ),
            ),
        )
        val viewModel = ReaderViewModel(
            openSession = { session },
            ioDispatcher = dispatcher,
        )
        viewModel.openLocal("/tmp/book.cbz", temp.root)
        dispatcher.scheduler.advanceUntilIdle()
        session.prefetchedRanges.clear()

        viewModel.reportPageDemand(5, "pager_target")
        dispatcher.scheduler.advanceUntilIdle()
        viewModel.reportPageDemand(5, "pager_target")
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(listOf(500L to 599L), session.prefetchedRanges)
    }

    @Test
    fun duplicateDemandPlannedRangePlansShareInFlightRangeBeforeRegistration() = runTest(dispatcher) {
        val prefetchStarted = CountDownLatch(1)
        val releasePrefetch = CountDownLatch(1)
        val session = BlockingPlannedRangeSession(
            pageCount = 8,
            plannedRangesByPage = mapOf(
                5 to listOf(
                    PlannedRemoteRange(start = 500, endInclusive = 599, pages = listOf(5), priority = 1),
                ),
            ),
            blockingRange = 500L to 599L,
            prefetchStarted = prefetchStarted,
            releasePrefetch = releasePrefetch,
        )
        val viewModel = ReaderViewModel(
            openSession = { session },
            ioDispatcher = DirectDispatcher,
        )
        viewModel.openLocal("/tmp/book.cbz", temp.root)
        dispatcher.scheduler.advanceUntilIdle()

        val firstDemand = Thread {
            viewModel.reportPageDemand(5, "pager_target")
        }
        firstDemand.start()
        assertTrue(prefetchStarted.await(1, TimeUnit.SECONDS))

        val secondDemand = Thread {
            viewModel.reportPageDemand(5, "pager_target")
        }
        secondDemand.start()
        Thread.sleep(100)

        assertEquals(listOf(500L to 599L), session.prefetchedRanges)
        releasePrefetch.countDown()
        firstDemand.join(1_000)
        secondDemand.join(1_000)
        assertEquals(listOf(500L to 599L), session.prefetchedRanges)
    }

    @Test
    fun lowPriorityPlannedRangePrefetchProtectsCompletedHighPriorityRangesFromSameGeneration() = runTest(dispatcher) {
        val session = FakeReaderSession(
            pageCount = 10,
            plannedRangesByPage = mapOf(
                5 to listOf(
                    PlannedRemoteRange(start = 500, endInclusive = 599, pages = listOf(5), priority = 1),
                ),
                6 to listOf(
                    PlannedRemoteRange(start = 900, endInclusive = 999, pages = listOf(6), priority = 5),
                ),
            ),
        )
        val viewModel = ReaderViewModel(
            openSession = { session },
            ioDispatcher = dispatcher,
        )
        viewModel.openLocal("/tmp/book.cbz", temp.root)
        dispatcher.scheduler.advanceUntilIdle()
        session.prefetchCalls.clear()

        viewModel.reportPageDemand(5, "pager_target")
        dispatcher.scheduler.advanceUntilIdle()
        viewModel.reportPageDemand(6, "pager_target")
        dispatcher.scheduler.advanceUntilIdle()

        val lowPriorityCall = session.prefetchCalls.single { it.start == 900L }
        assertEquals(listOf(500L..599L), lowPriorityCall.protectedRanges)
    }

    @Test
    fun lowPriorityPlannedRangePrefetchDoesNotProtectFarCompletedHighPriorityRanges() = runTest(dispatcher) {
        val session = FakeReaderSession(
            pageCount = 20,
            plannedRangesByPage = mapOf(
                5 to listOf(
                    PlannedRemoteRange(start = 500, endInclusive = 599, pages = listOf(5), priority = 1),
                ),
                12 to listOf(
                    PlannedRemoteRange(start = 1200, endInclusive = 1299, pages = listOf(12), priority = 5),
                ),
            ),
        )
        val viewModel = ReaderViewModel(
            openSession = { session },
            ioDispatcher = dispatcher,
        )
        viewModel.openLocal("/tmp/book.cbz", temp.root)
        dispatcher.scheduler.advanceUntilIdle()
        session.prefetchCalls.clear()

        viewModel.reportPageDemand(5, "pager_target")
        dispatcher.scheduler.advanceUntilIdle()
        viewModel.reportPageDemand(12, "pager_target")
        dispatcher.scheduler.advanceUntilIdle()

        val lowPriorityCall = session.prefetchCalls.single { it.start == 1200L }
        assertEquals(emptyList<LongRange>(), lowPriorityCall.protectedRanges)
    }

    @Test
    fun stalePagerTargetPlanDoesNotPrefetchAfterSessionGenerationChanges() = runTest(dispatcher) {
        val session = FakeReaderSession(
            pageCount = 8,
            plannedRangesByPage = mapOf(
                5 to listOf(
                    PlannedRemoteRange(start = 500, endInclusive = 599, pages = listOf(5), priority = 1),
                ),
            ),
        )
        val viewModel = ReaderViewModel(
            openSession = { session },
            ioDispatcher = dispatcher,
        )
        viewModel.openLocal("/tmp/book.cbz", temp.root)
        dispatcher.scheduler.advanceUntilIdle()
        session.prefetchedRanges.clear()

        viewModel.reportPageDemand(5, "pager_target")
        viewModel.closeReader()
        dispatcher.scheduler.advanceUntilIdle()

        assertTrue(session.prefetchedRanges.isEmpty())
    }

    @Test
    fun selectPageWaitsForInFlightPlannedRangeCoveringSelectedPage() = runTest(dispatcher) {
        val sink = CollectingReaderLogSink()
        ReaderDiagnosticLog.setSink(sink)
        ReaderDiagnosticLog.setMode(ReaderLoggingMode.DETAIL)
        val executor = java.util.concurrent.Executors.newFixedThreadPool(2)
        val ioDispatcher = executor.asCoroutineDispatcher()
        val prefetchStarted = CountDownLatch(1)
        val prefetchFinished = CountDownLatch(1)
        val releasePrefetch = CountDownLatch(1)
        val selectedLoadStarted = CountDownLatch(1)
        val session = BlockingPlannedRangeSession(
            pageCount = 8,
            plannedRangesByPage = mapOf(
                5 to listOf(
                    PlannedRemoteRange(start = 500, endInclusive = 599, pages = listOf(5), priority = 1),
                ),
            ),
            blockingRange = 500L to 599L,
            prefetchStarted = prefetchStarted,
            prefetchFinished = prefetchFinished,
            releasePrefetch = releasePrefetch,
            selectedPage = 5,
            selectedLoadStarted = selectedLoadStarted,
        )
        val viewModel = ReaderViewModel(
            openSession = { session },
            ioDispatcher = ioDispatcher,
        )
        try {
            viewModel.openLocal("/tmp/book.cbz", temp.root)
            waitUntil(timeoutMs = 1_000) {
                dispatcher.scheduler.advanceUntilIdle()
                viewModel.uiState.pageFiles.containsKey(4)
            }
            viewModel.reportPageDemand(5, "pager_target")
            waitUntil(timeoutMs = 1_000) {
                dispatcher.scheduler.advanceUntilIdle()
                prefetchStarted.count == 0L
            }
            viewModel.reportPageDemand(5, "pager_target")
            dispatcher.scheduler.advanceUntilIdle()
            assertEquals(listOf(500L to 599L), session.prefetchedRanges)

            viewModel.selectPage(5)
            dispatcher.scheduler.runCurrent()

            assertTrue(!selectedLoadStarted.await(150, TimeUnit.MILLISECONDS))
            releasePrefetch.countDown()
            waitUntil(timeoutMs = 1_000) {
                dispatcher.scheduler.advanceUntilIdle()
                viewModel.uiState.pageFiles.containsKey(5)
            }
            assertTrue(sink.lines.any { it.contains("prefetch_promoted page=5 source=planned_range_to_select") })
        } finally {
            ReaderDiagnosticLog.setMode(ReaderLoggingMode.SUMMARY)
            releasePrefetch.countDown()
            prefetchFinished.await(1, TimeUnit.SECONDS)
            dispatcher.scheduler.advanceUntilIdle()
            ioDispatcher.close()
            executor.shutdownNow()
        }
    }

    @Test
    fun overlappingPlannedRangeJobIsRetainedWhenNewPlanHasDifferentExactKey() = runTest(dispatcher) {
        val executor = java.util.concurrent.Executors.newFixedThreadPool(2)
        val ioDispatcher = executor.asCoroutineDispatcher()
        val prefetchStarted = CountDownLatch(1)
        val prefetchFinished = CountDownLatch(1)
        val releasePrefetch = CountDownLatch(1)
        val session = BlockingPlannedRangeSession(
            pageCount = 8,
            plannedRangeSequenceByPage = mapOf(
                5 to ArrayDeque(
                    listOf(
                        listOf(PlannedRemoteRange(start = 500, endInclusive = 599, pages = listOf(5, 6), priority = 1)),
                        listOf(PlannedRemoteRange(start = 480, endInclusive = 599, pages = listOf(5, 6), priority = 1)),
                    ),
                ),
            ),
            blockingRange = 500L to 599L,
            prefetchStarted = prefetchStarted,
            prefetchFinished = prefetchFinished,
            releasePrefetch = releasePrefetch,
        )
        val viewModel = ReaderViewModel(
            openSession = { session },
            ioDispatcher = ioDispatcher,
        )
        try {
            viewModel.openLocal("/tmp/book.cbz", temp.root)
            waitUntil(timeoutMs = 1_000) {
                dispatcher.scheduler.advanceUntilIdle()
                viewModel.uiState.pageFiles.containsKey(4)
            }
            viewModel.reportPageDemand(5, "pager_target")
            waitUntil(timeoutMs = 1_000) {
                dispatcher.scheduler.advanceUntilIdle()
                prefetchStarted.count == 0L
            }

            viewModel.reportPageDemand(5, "pager_target")
            dispatcher.scheduler.advanceUntilIdle()

            assertEquals(listOf(500L to 599L), session.prefetchedRanges)
        } finally {
            releasePrefetch.countDown()
            prefetchFinished.await(1, TimeUnit.SECONDS)
            dispatcher.scheduler.advanceUntilIdle()
            ioDispatcher.close()
            executor.shutdownNow()
        }
    }

    @Test
    fun nearbyPlannedRangeJobIsRetainedWhenViewportMovesWithinPrefetchWindow() = runTest(dispatcher) {
        val sink = CollectingReaderLogSink()
        ReaderDiagnosticLog.setSink(sink)
        val executor = java.util.concurrent.Executors.newFixedThreadPool(2)
        val ioDispatcher = executor.asCoroutineDispatcher()
        val prefetchStarted = CountDownLatch(1)
        val prefetchFinished = CountDownLatch(1)
        val releasePrefetch = CountDownLatch(1)
        val session = BlockingPlannedRangeSession(
            pageCount = 12,
            plannedRangesByPage = mapOf(
                5 to listOf(
                    PlannedRemoteRange(start = 500, endInclusive = 599, pages = listOf(5), priority = 1),
                ),
                6 to listOf(
                    PlannedRemoteRange(start = 600, endInclusive = 699, pages = listOf(6), priority = 1),
                ),
            ),
            blockingRange = 500L to 599L,
            prefetchStarted = prefetchStarted,
            prefetchFinished = prefetchFinished,
            releasePrefetch = releasePrefetch,
        )
        val viewModel = ReaderViewModel(
            openSession = { session },
            ioDispatcher = ioDispatcher,
        )
        try {
            viewModel.openLocal("/tmp/book.cbz", temp.root)
            waitUntil(timeoutMs = 1_000) {
                dispatcher.scheduler.advanceUntilIdle()
                viewModel.uiState.pageFiles.containsKey(4)
            }
            viewModel.reportPageDemand(5, "pager_target")
            waitUntil(timeoutMs = 1_000) {
                dispatcher.scheduler.advanceUntilIdle()
                prefetchStarted.count == 0L
            }

            viewModel.reportPageDemand(6, "pager_target")
            waitUntil(timeoutMs = 1_000) {
                dispatcher.scheduler.advanceUntilIdle()
                session.prefetchedRanges.contains(600L to 699L)
            }

            assertTrue(
                sink.lines.none { it.contains("planned_range_prefetch_cancelled reason=stale_plan") },
            )
        } finally {
            releasePrefetch.countDown()
            prefetchFinished.await(1, TimeUnit.SECONDS)
            dispatcher.scheduler.advanceUntilIdle()
            ioDispatcher.close()
            executor.shutdownNow()
        }
    }

    @Test
    fun selectPagePromotesExistingPrefetchInsteadOfCancellingForSelection() = runTest(dispatcher) {
        val sink = CollectingReaderLogSink()
        ReaderDiagnosticLog.setSink(sink)
        ReaderDiagnosticLog.setMode(ReaderLoggingMode.DETAIL)
        try {
            val session = FakeReaderSession(pageCount = 6)
            val viewModel = ReaderViewModel(
                openSession = { session },
                ioDispatcher = dispatcher,
            )

            viewModel.openLocal("/tmp/book.cbz", temp.root)
            dispatcher.scheduler.runCurrent()
            assertEquals(0, viewModel.uiState.currentPage)

            viewModel.selectPage(1)
            dispatcher.scheduler.advanceUntilIdle()

            assertTrue(sink.lines.any { it.contains("prefetch_promoted page=1 source=prefetch_to_select") })
            assertTrue(sink.lines.none { it.contains("prefetch_cancelled reason=select_page") })
            assertTrue(sink.lines.none { it.contains("prefetch_failed page=1") })
        } finally {
            ReaderDiagnosticLog.setMode(ReaderLoggingMode.SUMMARY)
        }
    }

    @Test
    fun openLocalUsesSessionSpecificPagePrefetchWindow() = runTest(dispatcher) {
        val session = LimitedPagePrefetchSession(
            pageCount = 8,
            forwardPrefetchPageCount = 2,
            backwardPrefetchPageCount = 0,
        )
        val viewModel = ReaderViewModel(
            openSession = { session },
            ioDispatcher = dispatcher,
        )

        viewModel.openLocal("/tmp/book.pdf", temp.root, initialPage = 3)
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(listOf(3, 4, 5), session.loadedPages)
        assertEquals(setOf(3, 4, 5), viewModel.uiState.pageFiles.keys)
    }

    @Test
    fun selectingCachedPageContinuesSessionSpecificForwardPrefetchWindow() = runTest(dispatcher) {
        val session = LimitedPagePrefetchSession(
            pageCount = 8,
            forwardPrefetchPageCount = 2,
            backwardPrefetchPageCount = 0,
        )
        val viewModel = ReaderViewModel(
            openSession = { session },
            ioDispatcher = dispatcher,
        )
        viewModel.openLocal("/tmp/book.pdf", temp.root, initialPage = 3)
        dispatcher.scheduler.advanceUntilIdle()

        viewModel.selectPage(4)
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(listOf(3, 4, 5, 6), session.loadedPages)
        assertEquals(setOf(3, 4, 5, 6), viewModel.uiState.pageFiles.keys)
    }

    @Test
    fun demandingCachedTargetPageContinuesSessionSpecificForwardPrefetchWindow() = runTest(dispatcher) {
        val session = LimitedPagePrefetchSession(
            pageCount = 8,
            forwardPrefetchPageCount = 2,
            backwardPrefetchPageCount = 0,
            advancePrefetchOnPageDemand = true,
        )
        val viewModel = ReaderViewModel(
            openSession = { session },
            ioDispatcher = dispatcher,
        )
        viewModel.openLocal("/tmp/book.pdf", temp.root, initialPage = 3)
        dispatcher.scheduler.advanceUntilIdle()

        viewModel.reportPageDemand(4, "pager_target")
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(listOf(3, 4, 5, 6), session.loadedPages)
        assertEquals(setOf(3, 4, 5, 6), viewModel.uiState.pageFiles.keys)
    }

    @Test
    fun pageCacheFilesAreScopedByComicKey() = runTest(dispatcher) {
        val firstSession = FakeReaderSession(pageCount = 1)
        val secondSession = FakeReaderSession(pageCount = 1)
        val viewModel = ReaderViewModel(ioDispatcher = dispatcher)

        viewModel.openExistingSession(firstSession, temp.root, initialPage = 0, comicKey = "first")
        dispatcher.scheduler.advanceUntilIdle()
        val firstPath = viewModel.uiState.pageFiles.getValue(0).absolutePath
        viewModel.closeReader()

        viewModel.openExistingSession(secondSession, temp.root, initialPage = 0, comicKey = "second")
        dispatcher.scheduler.advanceUntilIdle()
        val secondPath = viewModel.uiState.pageFiles.getValue(0).absolutePath

        assertTrue(firstPath.contains("first"))
        assertTrue(secondPath.contains("second"))
        assertTrue(firstPath != secondPath)
    }

    @Test
    fun openExistingSessionPublishesReaderKeyForScrollStateReset() = runTest(dispatcher) {
        val firstSession = FakeReaderSession(pageCount = 5)
        val secondSession = FakeReaderSession(pageCount = 5)
        val viewModel = ReaderViewModel(ioDispatcher = dispatcher)

        viewModel.openExistingSession(firstSession, temp.root, initialPage = 0, comicKey = "first")
        dispatcher.scheduler.advanceUntilIdle()
        viewModel.selectPage(3)
        dispatcher.scheduler.advanceUntilIdle()
        assertTrue(viewModel.uiState.readerKey.orEmpty().startsWith("first#"))
        assertEquals(3, viewModel.uiState.currentPage)

        viewModel.closeReader()
        dispatcher.scheduler.advanceUntilIdle()
        viewModel.openExistingSession(secondSession, temp.root, initialPage = 0, comicKey = "second")
        dispatcher.scheduler.advanceUntilIdle()

        assertTrue(viewModel.uiState.readerKey.orEmpty().startsWith("second#"))
        assertEquals(0, viewModel.uiState.currentPage)
    }

    @Test
    fun reopeningSameComicPublishesNewReaderKeyForScrollStateReset() = runTest(dispatcher) {
        val firstSession = FakeReaderSession(pageCount = 5)
        val secondSession = FakeReaderSession(pageCount = 5)
        val viewModel = ReaderViewModel(ioDispatcher = dispatcher)

        viewModel.openExistingSession(firstSession, temp.root, initialPage = 0, comicKey = "same")
        dispatcher.scheduler.advanceUntilIdle()
        val firstReaderKey = viewModel.uiState.readerKey

        viewModel.selectPage(3)
        dispatcher.scheduler.advanceUntilIdle()
        viewModel.closeReader()
        dispatcher.scheduler.advanceUntilIdle()
        viewModel.openExistingSession(secondSession, temp.root, initialPage = 0, comicKey = "same")
        dispatcher.scheduler.advanceUntilIdle()

        assertNotEquals(firstReaderKey, viewModel.uiState.readerKey)
        assertEquals(0, viewModel.uiState.currentPage)
    }

    @Test
    fun pageLoadPrunesPageCacheAfterExtraction() = runTest(dispatcher) {
        val session = FakeReaderSession(pageCount = 1)
        val protectedFiles = mutableListOf<File>()
        val maxBytes = mutableListOf<Long>()
        val viewModel = ReaderViewModel(
            openSession = { session },
            ioDispatcher = dispatcher,
            prunePageCache = { _, protectedFile, limitBytes ->
                protectedFiles += protectedFile
                maxBytes += limitBytes
            },
        )
        viewModel.updatePageCacheMaxBytes(2L * 1024L * 1024L * 1024L)

        viewModel.openLocal("/tmp/book.cbz", temp.root, comicKey = "cache-prune")
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(listOf(viewModel.uiState.pageFiles.getValue(0)), protectedFiles)
        assertEquals(listOf(2L * 1024L * 1024L * 1024L), maxBytes)
    }

    @Test
    fun closeReaderEmitsLocalPerformanceSummary() = runTest(dispatcher) {
        val sink = CollectingReaderLogSink()
        ReaderDiagnosticLog.setSink(sink)
        ReaderDiagnosticLog.setMode(ReaderLoggingMode.SUMMARY)
        try {
            val session = FakeReaderSession(pageCount = 2)
            val viewModel = ReaderViewModel(
                openSession = { session },
                ioDispatcher = dispatcher,
            )

            viewModel.openLocal("/tmp/book.cbz", temp.root)
            dispatcher.scheduler.advanceUntilIdle()
            viewModel.selectPage(1)
            dispatcher.scheduler.advanceUntilIdle()
            viewModel.closeReader()

            val summaryLine = sink.lines.singleOrNull { it.contains("local_session_summary") }
                ?: error("Missing local_session_summary in ${sink.lines}")
            assertTrue(summaryLine.contains("pagesLoaded=2"))
            assertTrue(summaryLine.contains("cacheHits=0"))
            assertTrue(summaryLine.contains("cacheMisses=2"))
            assertTrue(summaryLine.contains("totalOutputBytes=12"))
            assertTrue(summaryLine.contains("largestOutputBytes="))
            assertTrue(summaryLine.contains("slowestPage="))
            assertTrue(summaryLine.contains("slowestPageMs="))
            assertTrue(summaryLine.contains("slowestPageReason="))
        } finally {
            ReaderDiagnosticLog.clearSink()
            ReaderDiagnosticLog.setMode(ReaderLoggingMode.SUMMARY)
        }
    }

    @Test
    fun selectPageSavesReadingProgressWhenComicKeyIsPresent() = runTest(dispatcher) {
        val session = FakeReaderSession(pageCount = 5)
        val savedPages = mutableListOf<Pair<String, Int>>()
        val viewModel = ReaderViewModel(
            openSession = { session },
            ioDispatcher = dispatcher,
            savePage = { key, page -> savedPages += key to page },
        )
        viewModel.openLocal("/tmp/book.cbz", temp.root, comicKey = "comic-key")
        dispatcher.scheduler.advanceUntilIdle()

        viewModel.selectPage(2)
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(listOf("comic-key" to 2), savedPages)
    }

    @Test
    fun clearedClosesOpenSession() = runTest(dispatcher) {
        val session = FakeReaderSession(pageCount = 1)
        val viewModel = ReaderViewModel(
            openSession = { session },
            ioDispatcher = dispatcher,
        )
        viewModel.openLocal("/tmp/book.cbz", temp.root)
        dispatcher.scheduler.advanceUntilIdle()

        viewModel.closeReader()
        dispatcher.scheduler.advanceUntilIdle()

        assertTrue(session.closed)
    }

    @Test
    fun openRemoteShowsReaderLoadingStateBeforeRemoteOpenCompletes() = runTest(dispatcher) {
        val releaseOpen = CompletableDeferred<Unit>()
        val session = FakeReaderSession(pageCount = 1)
        val viewModel = ReaderViewModel(ioDispatcher = dispatcher)

        viewModel.openRemote(temp.root) {
            releaseOpen.await()
            OpenComicResult(
                comicKey = "remote-book",
                localFile = temp.newFile("remote.cbz"),
                session = session,
                initialPage = 0,
            )
        }

        assertTrue(viewModel.uiState.isLoading)
        assertEquals(0, viewModel.uiState.pageCount)

        releaseOpen.complete(Unit)
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(1, viewModel.uiState.pageCount)
        assertEquals(0, viewModel.uiState.currentPage)
    }

    @Test
    fun closeReaderCancelsPendingRemoteOpen() = runTest(dispatcher) {
        val cancelled = CompletableDeferred<Unit>()
        val viewModel = ReaderViewModel(ioDispatcher = dispatcher)

        viewModel.openRemote(temp.root) {
            try {
                CompletableDeferred<OpenComicResult>().await()
            } finally {
                cancelled.complete(Unit)
            }
        }
        dispatcher.scheduler.runCurrent()

        viewModel.closeReader()
        dispatcher.scheduler.advanceUntilIdle()

        assertTrue(cancelled.isCompleted)
        assertEquals(ReaderUiState(), viewModel.uiState)
    }

    @Test
    fun selectingUnreadyPageEmitsPageNotReadyAnalysisAfterImageSuccess() = runTest(dispatcher) {
        val sink = CollectingReaderLogSink()
        ReaderDiagnosticLog.setSink(sink)
        val session = FakeReaderSession(pageCount = 6)
        val clockValues = ArrayDeque(listOf(0L, 10L, 20L, 30L, 100L, 160L, 220L, 280L, 360L))
        val viewModel = ReaderViewModel(
            openSession = { session },
            ioDispatcher = dispatcher,
            elapsedRealtimeMs = { clockValues.removeFirstOrNull() ?: 360L },
        )
        viewModel.openLocal("/tmp/book.cbz", temp.root)
        dispatcher.scheduler.advanceUntilIdle()

        viewModel.reportPageDemand(5, "test")
        viewModel.selectPage(5)
        dispatcher.scheduler.advanceUntilIdle()
        viewModel.reportImageLoadStarted(5)
        viewModel.reportImageLoadSucceeded(5)

        assertTrue(sink.lines.any { it.contains("analysis page_not_ready page=5") })
    }

    @Test
    fun closeReaderDoesNotBlockCallerWhileNativeCloseRuns() = runTest(dispatcher) {
        val executor = java.util.concurrent.Executors.newSingleThreadExecutor()
        val ioDispatcher = executor.asCoroutineDispatcher()
        val closeStarted = CountDownLatch(1)
        val releaseClose = CountDownLatch(1)
        val closeFinished = CountDownLatch(1)
        val session = BlockingCloseSession(closeStarted, releaseClose, closeFinished)
        val viewModel = ReaderViewModel(ioDispatcher = ioDispatcher)

        viewModel.openExistingSession(session, temp.root, initialPage = 0, comicKey = "slow-close")
        waitUntil(timeoutMs = 1_000) {
            dispatcher.scheduler.advanceUntilIdle()
            viewModel.uiState.pageCount == 1
        }
        val startedAt = System.nanoTime()

        viewModel.closeReader()
        val elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt)

        assertTrue("closeReader blocked for ${elapsedMs}ms", elapsedMs < 100)
        assertTrue(closeStarted.await(1, TimeUnit.SECONDS))
        releaseClose.countDown()
        assertTrue(closeFinished.await(1, TimeUnit.SECONDS))
        ioDispatcher.close()
        executor.shutdown()
    }

    @Test
    fun selectPageDoesNotBlockCallerWhileNativeViewportUpdateRuns() = runTest(dispatcher) {
        val executor = java.util.concurrent.Executors.newSingleThreadExecutor()
        val ioDispatcher = executor.asCoroutineDispatcher()
        val session = BlockingViewportSession(pageCount = 3, blockingPage = 1)
        val viewModel = ReaderViewModel(
            openSession = { session },
            ioDispatcher = ioDispatcher,
        )
        viewModel.openLocal("/tmp/book.cbz", temp.root)
        waitUntil(timeoutMs = 1_000) {
            dispatcher.scheduler.advanceUntilIdle()
            viewModel.uiState.pageFiles.containsKey(1)
        }
        val startedAt = System.nanoTime()

        viewModel.selectPage(1)
        val elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt)

        assertTrue("selectPage blocked for ${elapsedMs}ms", elapsedMs < 100)
        dispatcher.scheduler.runCurrent()
        assertTrue(session.viewportStarted.await(1, TimeUnit.SECONDS))
        session.releaseViewport.countDown()
        waitUntil(timeoutMs = 1_000) {
            dispatcher.scheduler.advanceUntilIdle()
            session.viewportPages.contains(1)
        }
        assertTrue(session.viewportPages.containsAll(listOf(0, 1)))
        ioDispatcher.close()
        executor.shutdown()
    }

    private class FakeReaderSession(
        override val pageCount: Int,
        private val failOnPages: Set<Int> = emptySet(),
        private val plannedRangesByPage: Map<Int, List<PlannedRemoteRange>> = emptyMap(),
    ) : ComicReaderSession {
        val loadedPages = mutableListOf<Int>()
        val viewportPages = mutableListOf<Int>()
        val plannedRangePages = mutableListOf<Int>()
        val prefetchedRanges = mutableListOf<Pair<Long, Long>>()
        val prefetchCalls = mutableListOf<RangePrefetchCall>()
        var closed = false

        override fun loadPageToFile(pageIndex: Int, outputFile: File): File {
            loadedPages += pageIndex
            if (pageIndex in failOnPages) {
                error("page $pageIndex failed")
            }
            outputFile.writeText("page-$pageIndex")
            return outputFile
        }

        override fun close() {
            closed = true
        }

        override fun updateViewport(pageIndex: Int, networkClass: Int) {
            viewportPages += pageIndex
        }

        override fun plannedRanges(pageIndex: Int, networkClass: Int): List<PlannedRemoteRange> =
            plannedRangesByPage[pageIndex].orEmpty().also {
                plannedRangePages += pageIndex
            }

        override fun prefetchRange(start: Long, endInclusive: Long): Boolean {
            prefetchedRanges += start to endInclusive
            return true
        }

        override fun prefetchRange(
            start: Long,
            endInclusive: Long,
            priority: Int,
            protectedRanges: List<LongRange>,
        ): Boolean {
            prefetchCalls += RangePrefetchCall(start, endInclusive, priority, protectedRanges)
            prefetchedRanges += start to endInclusive
            return true
        }
    }

    private class ConcurrencyTrackingPlannedRangeSession(
        override val pageCount: Int,
        private val plannedRangesByPage: Map<Int, List<PlannedRemoteRange>>,
        private val blockingRange: Pair<Long, Long>,
        private val firstBlockedRangeStarted: CountDownLatch,
        private val releaseBlockedRange: CountDownLatch,
        private val blockingRanges: Set<Pair<Long, Long>> = setOf(blockingRange),
    ) : ComicReaderSession {
        private val lock = Any()
        private var activePrefetches = 0
        var maxConcurrentPrefetches = 0
            private set
        private val recordedPrefetchedRanges = mutableListOf<Pair<Long, Long>>()
        val prefetchedRanges: List<Pair<Long, Long>>
            get() = synchronized(lock) { recordedPrefetchedRanges.toList() }

        override fun loadPageToFile(pageIndex: Int, outputFile: File): File {
            outputFile.writeText("page-$pageIndex")
            return outputFile
        }

        override fun updateViewport(pageIndex: Int, networkClass: Int) = Unit

        override fun plannedRanges(pageIndex: Int, networkClass: Int): List<PlannedRemoteRange> =
            plannedRangesByPage[pageIndex].orEmpty()

        override fun prefetchRange(start: Long, endInclusive: Long): Boolean {
            synchronized(lock) {
                activePrefetches += 1
                maxConcurrentPrefetches = maxOf(maxConcurrentPrefetches, activePrefetches)
                recordedPrefetchedRanges += start to endInclusive
            }
            try {
                val range = start to endInclusive
                if (range in blockingRanges) {
                    firstBlockedRangeStarted.countDown()
                    releaseBlockedRange.await(2, TimeUnit.SECONDS)
                }
                return true
            } finally {
                synchronized(lock) {
                    activePrefetches -= 1
                }
            }
        }

        override fun diagnostics(): String = ""

        override fun close() = Unit
    }

    private class BlockingPlannedRangeSession(
        override val pageCount: Int,
        private val plannedRangesByPage: Map<Int, List<PlannedRemoteRange>> = emptyMap(),
        private val plannedRangeSequenceByPage: Map<Int, ArrayDeque<List<PlannedRemoteRange>>> = emptyMap(),
        private val blockingRange: Pair<Long, Long>,
        private val prefetchStarted: CountDownLatch,
        private val prefetchFinished: CountDownLatch = CountDownLatch(0),
        private val releasePrefetch: CountDownLatch,
        private val selectedPage: Int? = null,
        private val selectedLoadStarted: CountDownLatch? = null,
    ) : ComicReaderSession {
        private val lock = Any()
        val loadedPages = mutableListOf<Int>()
        private val recordedPrefetchedRanges = mutableListOf<Pair<Long, Long>>()
        val prefetchedRanges: List<Pair<Long, Long>>
            get() = synchronized(lock) { recordedPrefetchedRanges.toList() }

        override fun loadPageToFile(pageIndex: Int, outputFile: File): File {
            loadedPages += pageIndex
            if (pageIndex == selectedPage) {
                selectedLoadStarted?.countDown()
            }
            outputFile.writeText("page-$pageIndex")
            return outputFile
        }

        override fun plannedRanges(pageIndex: Int, networkClass: Int): List<PlannedRemoteRange> {
            plannedRangeSequenceByPage[pageIndex]?.let { sequence ->
                if (sequence.isNotEmpty()) return sequence.removeFirst()
            }
            return plannedRangesByPage[pageIndex].orEmpty()
        }

        override fun prefetchRange(start: Long, endInclusive: Long): Boolean {
            synchronized(lock) {
                recordedPrefetchedRanges += start to endInclusive
            }
            if (start to endInclusive == blockingRange) {
                prefetchStarted.countDown()
                releasePrefetch.await(2, TimeUnit.SECONDS)
                prefetchFinished.countDown()
            }
            return true
        }

        override fun close() = Unit
    }

    private class LimitedPagePrefetchSession(
        override val pageCount: Int,
        override val forwardPrefetchPageCount: Int,
        override val backwardPrefetchPageCount: Int,
        override val advancePrefetchOnPageDemand: Boolean = false,
    ) : ComicReaderSession {
        val loadedPages = mutableListOf<Int>()

        override fun loadPageToFile(pageIndex: Int, outputFile: File): File {
            loadedPages += pageIndex
            outputFile.writeText("page-$pageIndex")
            return outputFile
        }

        override fun close() = Unit
    }

    private class BlockingCloseSession(
        private val closeStarted: CountDownLatch,
        private val releaseClose: CountDownLatch,
        private val closeFinished: CountDownLatch,
    ) : ComicReaderSession {
        override val pageCount: Int = 1

        override fun loadPageToFile(pageIndex: Int, outputFile: File): File {
            outputFile.writeText("page-$pageIndex")
            return outputFile
        }

        override fun close() {
            closeStarted.countDown()
            releaseClose.await(2, TimeUnit.SECONDS)
            closeFinished.countDown()
        }
    }

    private class BlockingViewportSession(
        override val pageCount: Int,
        private val blockingPage: Int,
    ) : ComicReaderSession {
        val viewportPages = mutableListOf<Int>()
        val viewportStarted = CountDownLatch(1)
        val releaseViewport = CountDownLatch(1)

        override fun loadPageToFile(pageIndex: Int, outputFile: File): File {
            outputFile.writeText("page-$pageIndex")
            return outputFile
        }

        override fun updateViewport(pageIndex: Int, networkClass: Int) {
            viewportPages += pageIndex
            if (pageIndex == blockingPage) {
                viewportStarted.countDown()
                releaseViewport.await(500, TimeUnit.MILLISECONDS)
            }
        }

        override fun close() = Unit
    }

    private class CollectingReaderLogSink : ReaderLogSink {
        private val lock = Any()
        private val recordedLines = mutableListOf<String>()
        val lines: List<String>
            get() = synchronized(lock) { recordedLines.toList() }

        override fun log(line: String) {
            synchronized(lock) {
                recordedLines += line
            }
        }

        override fun logBlocking(line: String) {
            synchronized(lock) {
                recordedLines += line
            }
        }
    }

    private data class RangePrefetchCall(
        val start: Long,
        val endInclusive: Long,
        val priority: Int,
        val protectedRanges: List<LongRange>,
    )

    private object DirectDispatcher : CoroutineDispatcher() {
        override fun dispatch(context: kotlin.coroutines.CoroutineContext, block: Runnable) {
            block.run()
        }
    }

    private fun waitUntil(timeoutMs: Long, condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
        while (!condition() && System.nanoTime() < deadline) {
            Thread.sleep(10)
        }
        assertTrue("condition not met within ${timeoutMs}ms", condition())
    }
}
