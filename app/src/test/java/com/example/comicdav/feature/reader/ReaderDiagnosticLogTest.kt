package com.example.comicdav.feature.reader

import com.example.comicdav.CollectingReaderLogSink
import com.example.comicdav.data.ReaderLoggingMode
import com.example.comicdav.runReaderLogIo
import java.time.ZoneOffset
import java.time.ZonedDateTime
import kotlin.coroutines.ContinuationInterceptor
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
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
    fun readerLogDocumentCreationUsesTheInjectedIoDispatcher() = runTest {
        val ioDispatcher = StandardTestDispatcher(testScheduler)
        var observedDispatcher: ContinuationInterceptor? = null

        runReaderLogIo(ioDispatcher) {
            observedDispatcher = currentCoroutineContext()[ContinuationInterceptor]
        }

        assertSame(ioDispatcher, observedDispatcher)
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
                "prefetchStartedBeforeDemand=false queueOrWaitMs=unknown " +
                "extractMs=120 imageRenderMs=80",
            line,
        )
    }

    @Test
    fun summaryModeWritesSummaryButSkipsDetailBuilders() {
        val sink = CollectingReaderLogSink()
        ReaderDiagnosticLog.setSink(sink)
        ReaderDiagnosticLog.setMode(ReaderLoggingMode.SUMMARY)
        var detailBuilt = false
        try {
            ReaderDiagnosticLog.summary(ReaderLogCategory.SESSION) { "reader_open pageCount=2" }
            ReaderDiagnosticLog.detail(ReaderLogCategory.UI) {
                detailBuilt = true
                "pager current=1"
            }
        } finally {
            ReaderDiagnosticLog.clearSink()
            ReaderDiagnosticLog.setMode(ReaderLoggingMode.SUMMARY)
        }

        assertTrue(sink.lines.single().contains("level=summary category=SESSION reader_open pageCount=2"))
        assertFalse(detailBuilt)
    }

    @Test
    fun detailModeWritesDetailEvents() {
        val sink = CollectingReaderLogSink()
        ReaderDiagnosticLog.setSink(sink)
        ReaderDiagnosticLog.setMode(ReaderLoggingMode.DETAIL)
        try {
            ReaderDiagnosticLog.detail(ReaderLogCategory.UI) { "pager current=1" }
        } finally {
            ReaderDiagnosticLog.clearSink()
            ReaderDiagnosticLog.setMode(ReaderLoggingMode.SUMMARY)
        }

        assertTrue(sink.lines.single().contains("level=detail category=UI pager current=1"))
    }

    @Test
    fun redactionRemovesRawUriPathAndFileName() {
        val line = redactReaderLogText(
            "uri=content://provider/private/book.cbz path=/secret/books/book.cbz fileName=Secret Book.cbz",
        )

        assertFalse(line.contains("content://provider/private/book.cbz"))
        assertFalse(line.contains("/secret/books/book.cbz"))
        assertFalse(line.contains("Secret Book.cbz"))
        assertTrue(line.contains("uriId=local:"))
        assertTrue(line.contains("pathId=path:"))
        assertTrue(line.contains("fileExt=cbz"))
    }

    @Test
    fun redactionRemovesPathValuesWithSpaces() {
        val line = redactReaderLogText("path=/Books/Secret Book.cbz start=0")

        assertFalse(line.contains("/Books/Secret Book.cbz"))
        assertFalse(line.contains("Secret Book.cbz"))
        assertTrue(line.contains("pathId=path:"))
        assertTrue(line.contains("start=0"))
    }

    @Test
    fun offModeDoesNotInvokeSummaryOrDetailBuilders() {
        val sink = CollectingReaderLogSink()
        ReaderDiagnosticLog.setSink(sink)
        ReaderDiagnosticLog.setMode(ReaderLoggingMode.OFF)
        var summaryBuilt = false
        var detailBuilt = false
        try {
            ReaderDiagnosticLog.summary(ReaderLogCategory.SESSION) {
                summaryBuilt = true
                "summary"
            }
            ReaderDiagnosticLog.detail(ReaderLogCategory.UI) {
                detailBuilt = true
                "detail"
            }
        } finally {
            ReaderDiagnosticLog.clearSink()
            ReaderDiagnosticLog.setMode(ReaderLoggingMode.SUMMARY)
        }

        assertFalse(summaryBuilt)
        assertFalse(detailBuilt)
        assertTrue(sink.lines.isEmpty())
    }

}
