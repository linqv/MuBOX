package com.example.comicdav.feature.reader

import com.example.comicdav.core.diagnostics.DiagnosticCategory
import com.example.comicdav.core.diagnostics.Diagnostics
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
import java.util.Locale

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
    private val diagnostics: Diagnostics = ReaderDiagnosticLog,
    private val openDocumentSession: OpenLocalDocumentSessionFactory = { descriptor, fileName, format ->
        val document = RealMuPdfDocumentAdapter().open(descriptor, fileName, format)
        MuPdfReaderSession(document, format)
    },
    private val logDiagnostic: (() -> String) -> Unit = { event ->
        diagnostics.summary(DiagnosticCategory.LOCAL_FILE, event)
    },
    private val elapsedRealtimeMs: () -> Long = { System.nanoTime() / 1_000_000L },
) {
    @WorkerThread
    fun open(uri: Uri, fileName: String, avifImagesEnabled: Boolean = false): ComicReaderSession {
        localArchiveFormatForFileName(fileName)?.let { format ->
            val descriptorOpenStartMs = elapsedRealtimeMs()
            val descriptor = context.contentResolver.openFileDescriptor(uri, "r")
                ?: error("无法读取所选文件")
            val descriptorOpenEndMs = elapsedRealtimeMs()
            val size = descriptor.statSize.takeIf { it > 0L } ?: 0L
            val fd = descriptor.detachFd()
            val session = openSession(fd, size, format, avifImagesEnabled)
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
