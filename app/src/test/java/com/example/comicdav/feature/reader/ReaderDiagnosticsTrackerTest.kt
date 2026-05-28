package com.example.comicdav.feature.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReaderDiagnosticsTrackerTest {
    @Test
    fun localSessionSummaryClassifiesSlowestPageAsQueueWaitWhenNonExtractTimeDominates() {
        val tracker = ReaderDiagnosticsTracker(elapsedRealtimeMs = { 0L })

        tracker.recordPageLoadTiming(
            pageIndex = 7,
            reason = "prefetch",
            cacheHit = false,
            loadStartedAtMs = 100L,
            fileReadyAtMs = 1_100L,
            extractMs = 80L,
            fileSize = 123L,
        )

        val summary = requireNotNull(tracker.localSessionSummary())

        assertEquals(7, summary.slowestPage)
        assertEquals(1_000L, summary.slowestPageMs)
        assertEquals(920L, summary.slowestPageQueueOrWaitMs)
        assertEquals("queue-wait", summary.slowestPageReason)
    }

    @Test
    fun pageNotReadyAnalysisReportsQueueWaitAsLikelyCauseWhenItDominates() {
        var nowMs = 0L
        val tracker = ReaderDiagnosticsTracker(elapsedRealtimeMs = { nowMs })

        tracker.reset()
        nowMs = 1L
        tracker.markPrefetchPlanned(listOf(3))
        nowMs = 2L
        tracker.markPrefetchStarted(3)
        nowMs = 10L
        tracker.recordPageDemand(3, "continuous_visible")
        tracker.recordPageLoadTiming(
            pageIndex = 3,
            reason = "prefetch",
            cacheHit = false,
            loadStartedAtMs = 20L,
            fileReadyAtMs = 520L,
            extractMs = 60L,
            fileSize = 456L,
        )

        val analysis = requireNotNull(
            tracker.pageNotReadyAnalysisIfNeeded(
                pageIndex = 3,
                completedAtMs = 600L,
                imageRenderMs = 20L,
            ),
        )

        assertTrue(analysis, analysis.contains("queueOrWaitMs=440"))
        assertTrue(analysis, analysis.contains("likelyCause=queue_or_wait"))
    }
}
