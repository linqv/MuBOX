package com.example.comicdav.feature.reader.mupdf

import com.example.comicdav.core.model.media.LocalDocumentFormat
import com.example.comicdav.core.ports.ComicReaderSession
import com.example.comicdav.feature.reader.ReaderDiagnosticLog
import com.example.comicdav.core.diagnostics.DiagnosticCategory
import java.io.File
import java.util.Locale
import java.util.concurrent.CancellationException

class MuPdfReaderSession(
    private val document: MuPdfDocumentHandle,
    private val format: LocalDocumentFormat,
    private val maxPixels: Int = defaultMuPdfRenderMaxPixels(format),
    private val jpegQuality: Int = defaultMuPdfRenderJpegQuality(format),
    private val logDiagnostic: (() -> String) -> Unit = { event ->
        ReaderDiagnosticLog.detail(DiagnosticCategory.PAGE_LOAD, event)
    },
    private val elapsedRealtimeMs: () -> Long = { System.nanoTime() / 1_000_000L },
) : ComicReaderSession {
    override val pageCount: Int = document.pageCount
    override val forwardPrefetchPageCount: Int =
        if (format == LocalDocumentFormat.Pdf) 3 else 2
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
        var renderMs = 0L
        var renderMetrics: MuPdfRenderMetrics? = null
        try {
            outputFile.parentFile?.mkdirs()
            val renderStartMs = elapsedRealtimeMs()
            renderMetrics = document.renderPageToJpeg(
                pageIndex,
                outputFile,
                maxPixels,
                jpegQuality,
            )
            renderMs = (elapsedRealtimeMs() - renderStartMs).coerceAtLeast(0L)
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
        logDiagnostic {
            formatMuPdfRenderDone(
                format = format,
                pageIndex = pageIndex,
                pageCount = pageCount,
                renderMs = renderMs,
                outputBytes = outputFile.length(),
                maxPixels = maxPixels,
                quality = jpegQuality,
                metrics = renderMetrics,
            )
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

private fun formatMuPdfRenderDone(
    format: LocalDocumentFormat,
    pageIndex: Int,
    pageCount: Int,
    renderMs: Long,
    outputBytes: Long,
    maxPixels: Int,
    quality: Int,
    metrics: MuPdfRenderMetrics?,
): String = buildString {
    append("mupdf_render_done ")
    append("format=${format.displayName} ")
    append("page=$pageIndex ")
    append("pageCount=$pageCount ")
    append("renderMs=$renderMs ")
    append("outputBytes=$outputBytes ")
    append("maxPixels=$maxPixels ")
    append("quality=$quality")
    if (metrics != null) {
        append(" bounds=${metrics.boundsWidth.formatDiagnosticFloat()}x${metrics.boundsHeight.formatDiagnosticFloat()}")
        append(" scale=${metrics.scale.formatDiagnosticFloat()}")
        append(" estimatedPixels=${metrics.estimatedPixels}")
        append(" estimatedBytes=${metrics.estimatedBytes}")
        append(" pixmapMs=${metrics.pixmapMs}")
        append(" jpegMs=${metrics.jpegMs}")
    }
}

private fun Float.formatDiagnosticFloat(): String =
    String.format(Locale.US, "%.4f", this)

private fun defaultMuPdfRenderMaxPixels(format: LocalDocumentFormat): Int =
    if (format == LocalDocumentFormat.Pdf) {
        PDF_MUPDF_RENDER_MAX_PIXELS
    } else {
        DEFAULT_MUPDF_RENDER_MAX_PIXELS
    }

private fun defaultMuPdfRenderJpegQuality(format: LocalDocumentFormat): Int =
    if (format == LocalDocumentFormat.Pdf) {
        PDF_MUPDF_RENDER_JPEG_QUALITY
    } else {
        DEFAULT_MUPDF_RENDER_JPEG_QUALITY
    }
