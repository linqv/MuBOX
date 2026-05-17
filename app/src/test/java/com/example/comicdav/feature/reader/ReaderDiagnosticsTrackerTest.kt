package com.example.comicdav.feature.reader

import org.junit.Assert.assertEquals
import org.junit.Test

class ReaderDiagnosticsTrackerTest {
    @Test
    fun localSessionSummaryLabelsSlowestCacheMissAsDecodeRender() {
        val tracker = ReaderDiagnosticsTracker(elapsedRealtimeMs = { 0L })

        tracker.recordPageLoadTiming(
            pageIndex = 1,
            reason = "select",
            cacheHit = true,
            loadStartedAtMs = 10L,
            fileReadyAtMs = 15L,
            extractMs = 0L,
            fileSize = 100L,
        )
        tracker.recordPageLoadTiming(
            pageIndex = 2,
            reason = "prefetch",
            cacheHit = false,
            loadStartedAtMs = 20L,
            fileReadyAtMs = 80L,
            extractMs = 55L,
            fileSize = 200L,
        )

        val summary = tracker.localSessionSummary()

        assertEquals(2, summary?.slowestPage)
        assertEquals(60L, summary?.slowestPageMs)
        assertEquals("decode-render", summary?.slowestPageReason)
    }

    @Test
    fun localSessionSummaryLabelsSlowestCacheHitAsCacheRead() {
        val tracker = ReaderDiagnosticsTracker(elapsedRealtimeMs = { 0L })

        tracker.recordPageLoadTiming(
            pageIndex = 1,
            reason = "select",
            cacheHit = true,
            loadStartedAtMs = 10L,
            fileReadyAtMs = 50L,
            extractMs = 0L,
            fileSize = 100L,
        )
        tracker.recordPageLoadTiming(
            pageIndex = 2,
            reason = "prefetch",
            cacheHit = false,
            loadStartedAtMs = 60L,
            fileReadyAtMs = 80L,
            extractMs = 15L,
            fileSize = 200L,
        )

        val summary = tracker.localSessionSummary()

        assertEquals(1, summary?.slowestPage)
        assertEquals(40L, summary?.slowestPageMs)
        assertEquals("cache-read", summary?.slowestPageReason)
    }
}
