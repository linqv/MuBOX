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
import java.util.Locale

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
    private val logDiagnostic: (() -> String) -> Unit = { event ->
        ReaderDiagnosticLog.summary(ReaderLogCategory.LOCAL_FILE, event)
    },
    private val elapsedRealtimeMs: () -> Long = { System.nanoTime() / 1_000_000L },
) {
    fun open(uri: Uri, fileName: String): ComicReaderSession {
        localArchiveFormatForFileName(fileName)?.let { format ->
            val descriptorOpenStartMs = elapsedRealtimeMs()
            val descriptor = context.contentResolver.openFileDescriptor(uri, "r")
                ?: error("无法读取所选文件")
            val descriptorOpenEndMs = elapsedRealtimeMs()
            val size = descriptor.statSize.takeIf { it > 0L } ?: 0L
            val fd = descriptor.detachFd()
            val session = openSession(fd, size, format)
            val openSessionEndMs = elapsedRealtimeMs()
            logLocalOpenDone(
                engine = "native-archive",
                format = format.nativeName.uppercase(Locale.ROOT),
                sizeBytes = size,
                descriptorOpenMs = descriptorOpenEndMs - descriptorOpenStartMs,
                openSessionMs = openSessionEndMs - descriptorOpenEndMs,
                pageCount = session.pageCount,
                fileExt = safeFileExtension(fileName),
            )
            return session
        }

        localDocumentFormatForFileName(fileName)?.let { format ->
            val descriptorOpenStartMs = elapsedRealtimeMs()
            val descriptor = context.contentResolver.openFileDescriptor(uri, "r")
                ?: error("无法读取所选文件")
            val descriptorOpenEndMs = elapsedRealtimeMs()
            val size = descriptor.statSize.takeIf { it > 0L } ?: 0L
            val session = openDocumentSession(descriptor, fileName, format)
            val openSessionEndMs = elapsedRealtimeMs()
            logLocalOpenDone(
                engine = "mupdf-document",
                format = format.displayName,
                sizeBytes = size,
                descriptorOpenMs = descriptorOpenEndMs - descriptorOpenStartMs,
                openSessionMs = openSessionEndMs - descriptorOpenEndMs,
                pageCount = session.pageCount,
                fileExt = safeFileExtension(fileName),
            )
            return session
        }

        error("暂不支持这个本地阅读格式")
    }

    private fun logLocalOpenDone(
        engine: String,
        format: String,
        sizeBytes: Long,
        descriptorOpenMs: Long,
        openSessionMs: Long,
        pageCount: Int,
        fileExt: String,
    ) {
        logDiagnostic {
            "local_open_done engine=$engine " +
                "format=$format " +
                "sizeBytes=$sizeBytes " +
                "descriptorOpenMs=$descriptorOpenMs " +
                "openSessionMs=$openSessionMs " +
                "pageCount=$pageCount " +
                "fileExt=$fileExt"
        }
    }

    private fun safeFileExtension(fileName: String): String =
        fileName.substringAfterLast('.', missingDelimiterValue = "")
            .lowercase(Locale.ROOT)
}
