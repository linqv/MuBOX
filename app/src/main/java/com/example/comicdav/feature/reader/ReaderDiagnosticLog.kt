package com.example.comicdav.feature.reader

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import java.time.Instant
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

data class ReaderPagerSnapshot(
    val currentPage: Int,
    val settledPage: Int,
    val targetPage: Int,
    val offsetFraction: Float,
    val isScrollInProgress: Boolean,
    val uiCurrentPage: Int,
    val pageCount: Int,
)

interface ReaderLogSink {
    fun log(line: String)
    fun logBlocking(line: String)
}

object NoopReaderLogSink : ReaderLogSink {
    override fun log(line: String) = Unit
    override fun logBlocking(line: String) = Unit
}

class ContentUriReaderLogSink(
    context: Context,
    private val uri: Uri,
    private val scope: CoroutineScope,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) : ReaderLogSink {
    private val appContext = context.applicationContext
    private val lock = Any()

    override fun log(line: String) {
        scope.launch(dispatcher) {
            runCatching {
                logBlocking(line)
            }
        }
    }

    override fun logBlocking(line: String) {
        synchronized(lock) {
            appContext.contentResolver.openOutputStream(uri, "wa")?.bufferedWriter().use { writer ->
                requireNotNull(writer) { "Could not open reader log output" }
                writer.appendLine(line)
            }
        }
    }
}

data class ReaderLogFile(
    val fileName: String,
    val uri: String,
    val sink: ReaderLogSink,
)

fun timestampedReaderLogFileName(now: ZonedDateTime): String {
    val stamp = now.format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS", Locale.US))
    return "comicdav-reader-$stamp.log"
}

fun createReaderLogFile(
    context: Context,
    folderTreeUri: Uri,
    scope: CoroutineScope,
    now: ZonedDateTime = ZonedDateTime.now(),
): ReaderLogFile {
    val resolver = context.applicationContext.contentResolver
    val parentDocumentUri = DocumentsContract.buildDocumentUriUsingTree(
        folderTreeUri,
        DocumentsContract.getTreeDocumentId(folderTreeUri),
    )
    val fileName = timestampedReaderLogFileName(now)
    val fileUri = requireNotNull(
        DocumentsContract.createDocument(resolver, parentDocumentUri, "text/plain", fileName),
    ) { "Could not create reader log file in selected folder" }
    return ReaderLogFile(
        fileName = fileName,
        uri = fileUri.toString(),
        sink = ContentUriReaderLogSink(context, fileUri, scope),
    )
}

object ReaderDiagnosticLog {
    @Volatile
    private var sink: ReaderLogSink = NoopReaderLogSink

    fun setSink(nextSink: ReaderLogSink) {
        sink = nextSink
    }

    fun clearSink() {
        sink = NoopReaderLogSink
    }

    fun event(event: String) {
        runCatching {
            sink.log(formatReaderLogLine(event))
        }
    }

    fun error(event: String, error: Throwable) {
        runCatching {
            sink.log(formatReaderLogLine(formatThrowable(event, error)))
        }
    }

    fun errorBlocking(event: String, error: Throwable) {
        runCatching {
            sink.logBlocking(formatReaderLogLine(formatThrowable(event, error)))
        }
    }
}

fun formatPagerSnapshot(snapshot: ReaderPagerSnapshot): String {
    return "pager current=${snapshot.currentPage} " +
        "settled=${snapshot.settledPage} " +
        "target=${snapshot.targetPage} " +
        "offset=${String.format(Locale.US, "%.4f", snapshot.offsetFraction)} " +
        "scrolling=${snapshot.isScrollInProgress} " +
        "uiCurrent=${snapshot.uiCurrentPage} " +
        "pageCount=${snapshot.pageCount}"
}

fun formatReaderLogLine(event: String, now: () -> String = { Instant.now().toString() }): String {
    return "${now()} $event"
}

fun formatThrowable(event: String, error: Throwable): String {
    return "$event error=${error.javaClass.simpleName}: ${error.message}\n${error.stackTraceToString()}"
}
