package com.example.comicdav.feature.reader.mupdf

import android.os.ParcelFileDescriptor
import com.artifex.mupdf.fitz.ColorSpace
import com.artifex.mupdf.fitz.Document
import com.artifex.mupdf.fitz.Matrix
import com.example.comicdav.core.model.media.LocalDocumentFormat
import java.io.File
import kotlin.math.ceil
import kotlin.math.sqrt

class RealMuPdfDocumentAdapter : MuPdfDocumentAdapter {
    override fun open(
        descriptor: ParcelFileDescriptor,
        fileName: String,
        format: LocalDocumentFormat,
    ): MuPdfDocumentHandle {
        val stream = ParcelFileDescriptorSeekableInputStream(descriptor)
        var document: Document? = null
        var ownershipTransferred = false
        return try {
            val openedDocument = Document.openDocument(stream, fileName)
            document = openedDocument
            if (openedDocument.needsPassword()) {
                throw IllegalStateException("暂不支持加密或需要密码的文件")
            }
            if (openedDocument.isReflowable) {
                openedDocument.layout(DEFAULT_MUPDF_REFLOW_WIDTH, DEFAULT_MUPDF_REFLOW_HEIGHT, DEFAULT_MUPDF_REFLOW_EM)
            }
            val pageCount = openedDocument.countPages()
            if (pageCount <= 0) {
                throw IllegalStateException("这个文件没有可读取的页面")
            }
            val handle = RealMuPdfDocumentHandle(
                document = openedDocument,
                stream = stream,
                pageCount = pageCount,
            )
            ownershipTransferred = true
            handle
        } catch (error: OutOfMemoryError) {
            throw IllegalStateException("文件过大，内存不足，无法打开这个 ${format.displayName} 文件", error)
        } catch (error: Exception) {
            throw mapMuPdfOpenError(format, error)
        } finally {
            if (!ownershipTransferred) {
                runCatching { document?.destroy() }
                runCatching { stream.close() }
            }
        }
    }
}

class RealMuPdfDocumentHandle(
    private val document: Document,
    private val stream: ParcelFileDescriptorSeekableInputStream,
    override val pageCount: Int,
) : MuPdfDocumentHandle {
    private var isClosed = false

    override fun renderPageToJpeg(
        pageIndex: Int,
        outputFile: File,
        maxPixels: Int,
        quality: Int,
    ) {
        val page = document.loadPage(pageIndex)
        try {
            val bounds = page.bounds
            val width = bounds.x1 - bounds.x0
            val height = bounds.y1 - bounds.y0
            val scale = mupdfRenderScale(width, height, maxPixels)
            val pixmap = page.toPixmap(Matrix.Scale(scale), ColorSpace.DeviceRGB, false)
            try {
                outputFile.parentFile?.mkdirs()
                pixmap.saveAsJPEG(outputFile.absolutePath, quality)
            } finally {
                pixmap.destroy()
            }
        } finally {
            page.destroy()
        }
    }

    override fun close() {
        if (isClosed) return
        isClosed = true
        runCatching { document.destroy() }
        runCatching { stream.close() }
    }
}

fun mupdfRenderScale(
    width: Float,
    height: Float,
    maxPixels: Int,
    targetWidth: Float = DEFAULT_MUPDF_RENDER_TARGET_WIDTH,
): Float {
    if (width <= 0f || height <= 0f || maxPixels <= 0) return 1f
    val pixels = width * height
    val targetScale = (targetWidth / width).coerceAtLeast(1f)
    if (pixels * targetScale * targetScale <= maxPixels.toFloat()) return targetScale
    return sqrt(maxPixels.toFloat() / pixels)
}

private const val DEFAULT_MUPDF_REFLOW_WIDTH = 1080f
private const val DEFAULT_MUPDF_REFLOW_HEIGHT = 1920f
private const val DEFAULT_MUPDF_REFLOW_EM = 12f
private const val DEFAULT_MUPDF_RENDER_TARGET_WIDTH = 1600f

fun mapMuPdfOpenError(format: LocalDocumentFormat, error: Exception): Exception {
    val message = error.message.orEmpty()
    if (message == "这个文件没有可读取的页面" || message == "暂不支持加密或需要密码的文件") {
        return error
    }
    if (message.contains("drm", ignoreCase = true)) {
        return IllegalStateException("暂不支持受 DRM 保护的文件", error)
    }
    if (message.contains("password", ignoreCase = true) || message.contains("encrypted", ignoreCase = true)) {
        return IllegalStateException("暂不支持加密或需要密码的文件", error)
    }
    return IllegalStateException("无法打开这个 ${format.displayName} 文件", error)
}
