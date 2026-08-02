package org.mubox.reader.feature.reader.mupdf

import org.mubox.reader.core.model.media.LocalDocumentFormat
import java.io.File
import java.util.concurrent.CancellationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class MuPdfReaderSessionTest {
    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun pageCountComesFromDocument() {
        val document = FakeMuPdfDocument(pageCount = 3)
        val session = MuPdfReaderSession(document, LocalDocumentFormat.Pdf)

        assertEquals(3, session.pageCount)
    }

    @Test
    fun loadPageToFileSkipsRenderingWhenOutputAlreadyExists() {
        val output = temp.newFile("page.png").apply {
            writeText("cached")
        }
        val document = FakeMuPdfDocument(pageCount = 2)
        val session = MuPdfReaderSession(document, LocalDocumentFormat.Pdf)

        val result = session.loadPageToFile(1, output)

        assertEquals(output, result)
        assertEquals(emptyList<Int>(), document.renderedPages)
        assertEquals("cached", output.readText())
    }

    @Test
    fun loadPageToFileRendersRequestedPage() {
        val output = File(temp.root, "page-2.png")
        val document = FakeMuPdfDocument(pageCount = 4)
        val session = MuPdfReaderSession(document, LocalDocumentFormat.Epub)

        val result = session.loadPageToFile(2, output)

        assertEquals(output, result)
        assertEquals(listOf(2), document.renderedPages)
        assertEquals("rendered-2", output.readText())
    }

    @Test
    fun loadPageToFileForwardsMaxPixels() {
        val output = File(temp.root, "page-1.png")
        val document = FakeMuPdfDocument(pageCount = 2)
        val session = MuPdfReaderSession(document, LocalDocumentFormat.Pdf, maxPixels = 123_456)

        session.loadPageToFile(1, output)

        assertEquals(123_456, document.renderedMaxPixels)
    }

    @Test
    fun loadPageToFileRendersNonPdfWithDefaultQuality() {
        val output = File(temp.root, "page-1.img")
        val document = FakeMuPdfDocument(pageCount = 2)
        val session = MuPdfReaderSession(document, LocalDocumentFormat.Epub)

        session.loadPageToFile(1, output)

        assertEquals(DEFAULT_MUPDF_RENDER_JPEG_QUALITY, document.renderedJpegQuality)
    }

    @Test
    fun pdfUsesTunedRenderProfile() {
        val output = File(temp.root, "page-1.img")
        val document = FakeMuPdfDocument(pageCount = 2)
        val session = MuPdfReaderSession(document, LocalDocumentFormat.Pdf)

        session.loadPageToFile(1, output)

        assertEquals(PDF_MUPDF_RENDER_MAX_PIXELS, document.renderedMaxPixels)
        assertEquals(PDF_MUPDF_RENDER_JPEG_QUALITY, document.renderedJpegQuality)
    }

    @Test
    fun nonPdfDocumentsKeepDefaultRenderProfile() {
        val output = File(temp.root, "page-1.img")
        val document = FakeMuPdfDocument(pageCount = 2)
        val session = MuPdfReaderSession(document, LocalDocumentFormat.Epub)

        session.loadPageToFile(1, output)

        assertEquals(DEFAULT_MUPDF_RENDER_MAX_PIXELS, document.renderedMaxPixels)
        assertEquals(DEFAULT_MUPDF_RENDER_JPEG_QUALITY, document.renderedJpegQuality)
    }

    @Test
    fun loadPageToFileRejectsOutOfRangePage() {
        val output = File(temp.root, "page-9.png")
        val session = MuPdfReaderSession(FakeMuPdfDocument(pageCount = 2), LocalDocumentFormat.Mobi)

        val error = runCatching {
            session.loadPageToFile(9, output)
        }.exceptionOrNull()

        assertEquals("页面渲染失败", error?.message)
        assertFalse(output.exists())
    }

    @Test
    fun loadPageToFileRejectsOutOfRangePageBeforeCacheHit() {
        val output = temp.newFile("page-9.png").apply {
            writeText("cached")
        }
        val session = MuPdfReaderSession(FakeMuPdfDocument(pageCount = 2), LocalDocumentFormat.Mobi)

        val error = runCatching {
            session.loadPageToFile(9, output)
        }.exceptionOrNull()

        assertEquals("页面渲染失败", error?.message)
        assertEquals("cached", output.readText())
    }

    @Test
    fun loadPageToFileRethrowsCancellationException() {
        val output = File(temp.root, "page.png")
        val failure = CancellationException("cancelled")
        val document = FakeMuPdfDocument(pageCount = 1, renderFailure = failure)
        val session = MuPdfReaderSession(document, LocalDocumentFormat.Pdf)

        val thrown = catchThrowable {
            session.loadPageToFile(0, output)
        }

        assertSame(failure, thrown)
        assertFalse(output.exists())
    }

    @Test
    fun loadPageToFileRemovesPartialOutputOnCancellation() {
        val output = File(temp.root, "page.png")
        val failure = CancellationException("cancelled")
        val document = FakeMuPdfDocument(
            pageCount = 1,
            renderFailure = failure,
            partialRenderText = "partial",
        )
        val session = MuPdfReaderSession(document, LocalDocumentFormat.Pdf)

        val thrown = catchThrowable {
            session.loadPageToFile(0, output)
        }

        assertSame(failure, thrown)
        assertTrue(!output.exists() || output.length() == 0L)
    }

    @Test
    fun loadPageToFileMapsOutOfMemoryToReadableFailure() {
        val output = File(temp.root, "page.png")
        val failure = OutOfMemoryError("oom")
        val document = FakeMuPdfDocument(pageCount = 1, renderFailure = failure)
        val session = MuPdfReaderSession(document, LocalDocumentFormat.Pdf)

        val thrown = catchThrowable {
            session.loadPageToFile(0, output)
        }

        assertEquals("页面过大，内存不足，无法渲染", thrown?.message)
        assertSame(failure, thrown?.cause)
        assertFalse(output.exists())
    }

    @Test
    fun pdfPrefetchesThreeForwardPages() {
        val session = MuPdfReaderSession(FakeMuPdfDocument(pageCount = 1), LocalDocumentFormat.Pdf)

        assertEquals(3, session.forwardPrefetchPageCount)
        assertEquals(0, session.backwardPrefetchPageCount)
    }

    @Test
    fun nonPdfDocumentsKeepTwoForwardPrefetchPages() {
        val session = MuPdfReaderSession(FakeMuPdfDocument(pageCount = 1), LocalDocumentFormat.Epub)

        assertEquals(2, session.forwardPrefetchPageCount)
        assertEquals(0, session.backwardPrefetchPageCount)
    }

    @Test
    fun diagnosticsIncludesReaderFormatAndPageCount() {
        val session = MuPdfReaderSession(FakeMuPdfDocument(pageCount = 7), LocalDocumentFormat.Epub)

        assertEquals("reader=mupdf;format=EPUB;pageCount=7", session.diagnostics())
    }

    @Test
    fun closeClosesDocumentOnce() {
        val document = FakeMuPdfDocument(pageCount = 1)
        val session = MuPdfReaderSession(document, LocalDocumentFormat.Azw3)

        session.close()
        session.close()

        assertTrue(document.closed)
        assertEquals(1, document.closeCount)
    }

    private class FakeMuPdfDocument(
        override val pageCount: Int,
        private val renderFailure: Throwable? = null,
        private val partialRenderText: String? = null,
    ) : MuPdfDocumentHandle {
        val renderedPages = mutableListOf<Int>()
        var renderedMaxPixels: Int? = null
        var renderedJpegQuality: Int? = null
        var closed = false
        var closeCount = 0

        override fun renderPageToJpeg(
            pageIndex: Int,
            outputFile: File,
            maxPixels: Int,
            quality: Int,
        ) {
            if (pageIndex !in 0 until pageCount) error("bad page")
            partialRenderText?.let { outputFile.writeText(it) }
            renderFailure?.let { throw it }
            renderedPages += pageIndex
            renderedMaxPixels = maxPixels
            renderedJpegQuality = quality
            outputFile.writeText("rendered-$pageIndex")
        }

        override fun close() {
            closeCount++
            closed = true
        }
    }

    private fun catchThrowable(block: () -> Unit): Throwable? =
        try {
            block()
            null
        } catch (throwable: Throwable) {
            throwable
        }
}
