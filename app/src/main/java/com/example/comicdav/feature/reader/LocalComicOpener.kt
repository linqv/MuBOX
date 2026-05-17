package com.example.comicdav.feature.reader

import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import com.example.comicdav.data.LocalArchiveFormat
import com.example.comicdav.data.LocalDocumentFormat
import com.example.comicdav.data.localArchiveFormatForFileName
import com.example.comicdav.data.localDocumentFormatForFileName
import com.example.comicdav.feature.reader.mupdf.MuPdfReaderSession
import com.example.comicdav.feature.reader.mupdf.RealMuPdfDocumentAdapter
import com.example.comicdav.nativebridge.ComicEngine
import com.example.comicdav.nativebridge.ComicReaderSession

typealias OpenLocalFdSessionFactory = (
    fd: Int,
    size: Long,
    format: LocalArchiveFormat,
) -> ComicReaderSession

typealias OpenLocalDocumentSessionFactory = (
    descriptor: ParcelFileDescriptor,
    fileName: String,
    format: LocalDocumentFormat,
) -> ComicReaderSession

class LocalComicOpener(
    private val context: Context,
    private val openSession: OpenLocalFdSessionFactory = { fd, size, format ->
        ComicEngine().openLocalFd(fd, size, format.nativeName)
    },
    private val openDocumentSession: OpenLocalDocumentSessionFactory = { descriptor, fileName, format ->
        val document = RealMuPdfDocumentAdapter().open(descriptor, fileName, format)
        MuPdfReaderSession(document, format)
    },
) {
    fun open(uri: Uri, fileName: String): ComicReaderSession {
        localArchiveFormatForFileName(fileName)?.let { format ->
            val descriptor = context.contentResolver.openFileDescriptor(uri, "r")
                ?: error("无法读取所选文件")
            val size = descriptor.statSize.takeIf { it > 0L } ?: 0L
            val fd = descriptor.detachFd()
            return openSession(fd, size, format)
        }

        localDocumentFormatForFileName(fileName)?.let { format ->
            val descriptor = context.contentResolver.openFileDescriptor(uri, "r")
                ?: error("无法读取所选文件")
            return openDocumentSession(descriptor, fileName, format)
        }

        error("暂不支持这个本地阅读格式")
    }
}
