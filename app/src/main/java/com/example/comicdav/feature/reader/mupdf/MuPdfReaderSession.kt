package com.example.comicdav.feature.reader.mupdf

import com.example.comicdav.data.LocalDocumentFormat
import com.example.comicdav.nativebridge.ComicReaderSession
import java.io.File
import java.util.concurrent.CancellationException

class MuPdfReaderSession(
    private val document: MuPdfDocumentHandle,
    private val format: LocalDocumentFormat,
    private val maxPixels: Int = DEFAULT_MUPDF_RENDER_MAX_PIXELS,
) : ComicReaderSession {
    override val pageCount: Int = document.pageCount
    override val forwardPrefetchPageCount: Int = 2
    override val backwardPrefetchPageCount: Int = 0
    override val advancePrefetchOnPageDemand: Boolean = true

    private var isClosed = false

    override fun loadPageToFile(pageIndex: Int, outputFile: File): File {
        if (pageIndex !in 0 until pageCount) {
            throw IllegalStateException("页面渲染失败")
        }
        if (outputFile.isFile && outputFile.length() > 0L) {
            return outputFile
        }
        var renderSucceeded = false
        try {
            outputFile.parentFile?.mkdirs()
            document.renderPageToJpeg(pageIndex, outputFile, maxPixels, DEFAULT_MUPDF_RENDER_JPEG_QUALITY)
            renderSucceeded = true
        } catch (exception: CancellationException) {
            throw exception
        } catch (error: OutOfMemoryError) {
            throw IllegalStateException("页面过大，内存不足，无法渲染", error)
        } catch (exception: Exception) {
            throw IllegalStateException("页面渲染失败", exception)
        } finally {
            if (!renderSucceeded) {
                outputFile.delete()
            }
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
