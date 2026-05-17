package com.example.comicdav.feature.reader.mupdf

import com.example.comicdav.data.LocalDocumentFormat
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
    ) : MuPdfDocumentHandle {
        val renderedPages = mutableListOf<Int>()
        var closed = false
        var closeCount = 0

        override fun renderPageToPng(pageIndex: Int, outputFile: File, maxPixels: Int) {
            if (pageIndex !in 0 until pageCount) error("bad page")
            renderedPages += pageIndex
            outputFile.writeText("rendered-$pageIndex")
        }

        override fun close() {
            closeCount++
            closed = true
        }
    }
}
