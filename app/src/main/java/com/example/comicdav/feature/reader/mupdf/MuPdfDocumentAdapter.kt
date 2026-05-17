package com.example.comicdav.feature.reader.mupdf

import android.os.ParcelFileDescriptor
import com.example.comicdav.data.LocalDocumentFormat
import java.io.Closeable
import java.io.File

interface MuPdfDocumentAdapter {
    fun open(
        descriptor: ParcelFileDescriptor,
        fileName: String,
        format: LocalDocumentFormat,
    ): MuPdfDocumentHandle
}

interface MuPdfDocumentHandle : Closeable {
    val pageCount: Int

    fun renderPageToJpeg(
        pageIndex: Int,
        outputFile: File,
        maxPixels: Int = DEFAULT_MUPDF_RENDER_MAX_PIXELS,
        quality: Int = DEFAULT_MUPDF_RENDER_JPEG_QUALITY,
    )
}

const val DEFAULT_MUPDF_RENDER_MAX_PIXELS: Int = 4_000_000
const val DEFAULT_MUPDF_RENDER_JPEG_QUALITY: Int = 92
