package com.example.comicdav.feature.reader

import java.time.ZoneOffset
import java.time.ZonedDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReaderDiagnosticLogTest {
    @Test
    fun formatPagerSnapshotIncludesAllPagerFields() {
        val line = formatPagerSnapshot(
            ReaderPagerSnapshot(
                currentPage = 2,
                settledPage = 1,
                targetPage = 3,
                offsetFraction = -0.2f,
                isScrollInProgress = true,
                uiCurrentPage = 1,
                pageCount = 8,
            ),
        )

        assertEquals(
            "pager current=2 settled=1 target=3 offset=-0.2000 scrolling=true uiCurrent=1 pageCount=8",
            line,
        )
    }

    @Test
    fun formatLogLineUsesTimestampAndEvent() {
        assertEquals("2026-05-14T00:00:00Z reader_open pageCount=8", formatReaderLogLine("reader_open pageCount=8") { "2026-05-14T00:00:00Z" })
    }

    @Test
    fun formatThrowableIncludesStackTraceText() {
        val line = formatThrowable("reader_crash", IllegalStateException("bad state"))

        assertTrue(line.startsWith("reader_crash error=IllegalStateException: bad state\n"))
        assertTrue(line.contains("IllegalStateException"))
    }

    @Test
    fun timestampedReaderLogFileNameUsesStableSortableFormat() {
        val timestamp = ZonedDateTime.of(
            2026,
            5,
            14,
            9,
            8,
            7,
            123_000_000,
            ZoneOffset.UTC,
        )

        assertEquals(
            "comicdav-reader-20260514-090807-123.log",
            timestampedReaderLogFileName(timestamp),
        )
    }

    @Test
    fun readerLogFileMetadataKeepsFileNameAndUri() {
        val sink = object : ReaderLogSink {
            override fun log(line: String) = Unit
            override fun logBlocking(line: String) = Unit
        }

        val file = ReaderLogFile(
            fileName = "comicdav-reader-20260514-090807-123.log",
            uri = "content://logs/tree/file",
            sink = sink,
        )

        assertEquals("comicdav-reader-20260514-090807-123.log", file.fileName)
        assertEquals("content://logs/tree/file", file.uri)
        assertEquals(sink, file.sink)
    }

    @Test
    fun firstImageAnalysisUsesLargestKnownSegment() {
        val line = formatFirstImageAnalysis(
            FirstImageTiming(
                page = 0,
                totalMs = 1_500,
                remoteOpenMs = 300,
                sessionInitialPageMs = 250,
                pageExtractMs = 900,
                imageRenderMs = 50,
                cacheHit = false,
            ),
        )

        assertEquals(
            "analysis first_image page=0 totalMs=1500 likelyCause=page_extract " +
                "remoteOpenMs=300 sessionInitialPageMs=250 pageExtractMs=900 " +
                "imageRenderMs=50 cacheHit=false",
            line,
        )
    }

    @Test
    fun pageNotReadyAnalysisPrefersMissingPrefetchEvidence() {
        val line = formatPageNotReadyAnalysis(
            PageNotReadyTiming(
                page = 3,
                waitMs = 700,
                wasPrefetchPlanned = false,
                wasPrefetchCancelled = false,
                prefetchStartedBeforeDemand = false,
                extractMs = 120,
                imageRenderMs = 80,
            ),
        )

        assertEquals(
            "analysis page_not_ready page=3 waitMs=700 likelyCause=not_prefetched " +
                "wasPrefetchPlanned=false wasPrefetchCancelled=false " +
                "prefetchStartedBeforeDemand=false extractMs=120 imageRenderMs=80",
            line,
        )
    }
}
