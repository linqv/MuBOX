package com.example.comicdav.feature.reader.mupdf

import com.example.comicdav.data.LocalDocumentFormat
import com.example.comicdav.nativebridge.ComicReaderSession
import java.io.File

class MuPdfReaderSession(
    private val document: MuPdfDocumentHandle,
    private val format: LocalDocumentFormat,
    private val maxPixels: Int = DEFAULT_MUPDF_RENDER_MAX_PIXELS,
) : ComicReaderSession {
    override val pageCount: Int = document.pageCount

    private var isClosed = false

    override fun loadPageToFile(pageIndex: Int, outputFile: File): File {
        if (pageIndex !in 0 until pageCount) {
            throw IllegalStateException("页面渲染失败")
        }
        if (outputFile.isFile && outputFile.length() > 0L) {
            return outputFile
        }
        runCatching {
            outputFile.parentFile?.mkdirs()
            document.renderPageToPng(pageIndex, outputFile, maxPixels)
        }.getOrElse {
            outputFile.delete()
            throw IllegalStateException("页面渲染失败", it)
        }
        if (!outputFile.isFile || outputFile.length() == 0L) {
            outputFile.delete()
            throw IllegalStateException("页面渲染失败")
        }
        return outputFile
    }

    override fun close() {
        if (isClosed) return
        isClosed = true
        document.close()
    }

    override fun diagnostics(): String =
        "reader=mupdf;format=${format.displayName};pageCount=$pageCount"
}
