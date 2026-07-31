package com.example.comicdav.feature.reader

import com.example.comicdav.core.diagnostics.DiagnosticCategory
import com.example.comicdav.core.diagnostics.ConfigurableDiagnostics
import com.example.comicdav.core.diagnostics.DiagnosticSink
import com.example.comicdav.core.diagnostics.DiagnosticVerbosity
import com.example.comicdav.CollectingReaderLogSink
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
        val sink = object : DiagnosticSink {
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
        val diagnostics = ConfigurableDiagnostics(
            defaultSink = sink,
            initialVerbosity = DiagnosticVerbosity.SUMMARY,
        )
        var detailBuilt = false
        diagnostics.summary(DiagnosticCategory.SESSION) { "reader_open pageCount=2" }
        diagnostics.detail(DiagnosticCategory.UI) {
            detailBuilt = true
            "pager current=1"
        }

        assertTrue(sink.lines.single().contains("level=summary category=SESSION reader_open pageCount=2"))
        assertFalse(detailBuilt)
    }

    @Test
    fun detailModeWritesDetailEvents() {
        val sink = CollectingReaderLogSink()
        val diagnostics = ConfigurableDiagnostics(
            defaultSink = sink,
            initialVerbosity = DiagnosticVerbosity.DETAIL,
        )
        diagnostics.detail(DiagnosticCategory.UI) { "pager current=1" }

        assertTrue(sink.lines.single().contains("level=detail category=UI pager current=1"))
    }

    @Test
    fun diagnosticInstancesDoNotShareSinkOrVerbosity() {
        val summarySink = CollectingReaderLogSink()
        val detailSink = CollectingReaderLogSink()
        val summaryDiagnostics = ConfigurableDiagnostics(
            defaultSink = summarySink,
            initialVerbosity = DiagnosticVerbosity.SUMMARY,
        )
        val detailDiagnostics = ConfigurableDiagnostics(
            defaultSink = detailSink,
            initialVerbosity = DiagnosticVerbosity.DETAIL,
        )

        summaryDiagnostics.detail(DiagnosticCategory.UI) { "summary-instance-detail" }
        summaryDiagnostics.summary(DiagnosticCategory.SESSION) { "summary-instance-event" }
        detailDiagnostics.detail(DiagnosticCategory.UI) { "detail-instance-event" }

        assertEquals(1, summarySink.lines.size)
        assertTrue(summarySink.lines.single().contains("summary-instance-event"))
        assertEquals(1, detailSink.lines.size)
        assertTrue(detailSink.lines.single().contains("detail-instance-event"))
        assertFalse(summarySink.lines.any { it.contains("detail-instance-event") })
        assertFalse(detailSink.lines.any { it.contains("summary-instance-event") })
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
        val diagnostics = ConfigurableDiagnostics(
            defaultSink = sink,
            initialVerbosity = DiagnosticVerbosity.OFF,
        )
        var summaryBuilt = false
        var detailBuilt = false
        diagnostics.summary(DiagnosticCategory.SESSION) {
            summaryBuilt = true
            "summary"
        }
        diagnostics.detail(DiagnosticCategory.UI) {
            detailBuilt = true
            "detail"
        }

        assertFalse(summaryBuilt)
        assertFalse(detailBuilt)
        assertTrue(sink.lines.isEmpty())
    }

}
