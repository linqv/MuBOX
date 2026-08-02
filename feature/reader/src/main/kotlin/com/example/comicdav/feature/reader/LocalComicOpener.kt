package com.example.comicdav.feature.reader

import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.annotation.WorkerThread
import com.example.comicdav.core.model.media.LocalArchiveFormat
import com.example.comicdav.core.model.media.LocalDocumentFormat
import com.example.comicdav.core.model.media.localArchiveFormatForFileName
import com.example.comicdav.core.model.media.localDocumentFormatForFileName
import com.example.comicdav.core.ports.ComicReaderSession
import com.example.comicdav.feature.reader.mupdf.MuPdfReaderSession
import com.example.comicdav.feature.reader.mupdf.RealMuPdfDocumentAdapter

typealias OpenLocalFdSessionFactory = (
    fd: Int,
    size: Long,
    format: LocalArchiveFormat,
    avifImagesEnabled: Boolean,
) -> ComicReaderSession

typealias OpenLocalDocumentSessionFactory = (
    descriptor: ParcelFileDescriptor,
    fileName: String,
    format: LocalDocumentFormat,
) -> ComicReaderSession

class LocalComicOpener(
    private val context: Context,
    private val openSession: OpenLocalFdSessionFactory,
    private val openDocumentSession: OpenLocalDocumentSessionFactory = { descriptor, fileName, format ->
        val document = RealMuPdfDocumentAdapter().open(descriptor, fileName, format)
        MuPdfReaderSession(document, format)
    },
) {
    @WorkerThread
    fun open(uri: Uri, fileName: String, avifImagesEnabled: Boolean = false): ComicReaderSession {
        localArchiveFormatForFileName(fileName)?.let { format ->
            val descriptor = context.contentResolver.openFileDescriptor(uri, "r")
                ?: error("无法读取所选文件")
            val size = descriptor.statSize.takeIf { it > 0L } ?: 0L
            val fd = descriptor.detachFd()
            return openSession(fd, size, format, avifImagesEnabled)
        }

        localDocumentFormatForFileName(fileName)?.let { format ->
            val descriptor = context.contentResolver.openFileDescriptor(uri, "r")
                ?: error("无法读取所选文件")
            return openDocumentSession(descriptor, fileName, format)
        }

        error("暂不支持这个本地阅读格式")
    }
}
