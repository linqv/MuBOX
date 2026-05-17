package com.example.comicdav.feature.reader

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import com.example.comicdav.data.ReaderLoggingMode
import java.security.MessageDigest
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

enum class ReaderLogCategory {
    SESSION,
    LOCAL_FILE,
    PAGE_LOAD,
    IMAGE,
    PREFETCH,
    RANGE_CACHE,
    UI,
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

    @Volatile
    private var mode: ReaderLoggingMode = ReaderLoggingMode.SUMMARY

    fun setSink(nextSink: ReaderLogSink) {
        sink = nextSink
    }

    fun clearSink() {
        sink = NoopReaderLogSink
    }

    fun setMode(nextMode: ReaderLoggingMode) {
        mode = nextMode
    }

    fun summary(category: ReaderLogCategory, event: () -> String) {
        if (mode == ReaderLoggingMode.OFF) return
        write(level = "summary", category = category, event = event)
    }

    fun detail(category: ReaderLogCategory, event: () -> String) {
        if (mode != ReaderLoggingMode.DETAIL) return
        write(level = "detail", category = category, event = event)
    }

    fun event(event: String) {
        summary(ReaderLogCategory.SESSION) { event }
    }

    fun error(category: ReaderLogCategory, event: String, error: Throwable) {
        if (mode == ReaderLoggingMode.OFF) return
        runCatching {
            sink.log(formatReaderLogLine("level=error category=${category.name} ${redactReaderLogText(formatThrowable(event, error))}"))
        }
    }

    fun error(event: String, error: Throwable) {
        error(ReaderLogCategory.SESSION, event, error)
    }

    fun errorBlocking(category: ReaderLogCategory, event: String, error: Throwable) {
        if (mode == ReaderLoggingMode.OFF) return
        runCatching {
            sink.logBlocking(formatReaderLogLine("level=error category=${category.name} ${redactReaderLogText(formatThrowable(event, error))}"))
        }
    }

    fun errorBlocking(event: String, error: Throwable) {
        errorBlocking(ReaderLogCategory.SESSION, event, error)
    }

    private fun write(level: String, category: ReaderLogCategory, event: () -> String) {
        runCatching {
            sink.log(formatReaderLogLine("level=$level category=${category.name} ${redactReaderLogText(event())}"))
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

fun redactReaderLogText(text: String): String {
    var redacted = text
    redacted = replaceToken(redacted, "uri", "uriId=local")
    redacted = replaceToken(redacted, "folderUri", "uriId=folder")
    redacted = replaceToken(redacted, "path", "pathId=path")
    redacted = replaceFileNameToken(redacted)
    return redacted
}

fun readerLogId(prefix: String, raw: String): String = "$prefix:${shortHash(raw)}"

private fun replaceToken(text: String, token: String, replacementName: String): String {
    val regex = Regex("""\b$token=(.+?)(?=\s+\w+=|$)""")
    return regex.replace(text) { match ->
        "$replacementName:${shortHash(match.groupValues[1].trim())}"
    }
}

private fun replaceFileNameToken(text: String): String {
    val regex = Regex("""\bfileName=(.+?)(?=\s+\w+=|$)""")
    return regex.replace(text) { match ->
        val raw = match.groupValues[1].trim()
        val extension = raw.substringAfterLast('.', missingDelimiterValue = "")
            .lowercase(Locale.ROOT)
        buildString {
            append("fileId=file:")
            append(shortHash(raw))
            if (extension.isNotBlank()) {
                append(" fileExt=")
                append(extension)
            }
        }
    }
}

private fun shortHash(raw: String): String {
    val digest = MessageDigest.getInstance("SHA-256")
        .digest(raw.toByteArray(Charsets.UTF_8))
    return digest.take(4).joinToString("") { byte -> "%02x".format(byte) }
}

data class FirstImageTiming(
    val page: Int,
    val totalMs: Long,
    val remoteOpenMs: Long? = null,
    val sessionInitialPageMs: Long? = null,
    val pageExtractMs: Long? = null,
    val imageRenderMs: Long? = null,
    val cacheHit: Boolean,
)

data class PageNotReadyTiming(
    val page: Int,
    val waitMs: Long,
    val wasPrefetchPlanned: Boolean,
    val wasPrefetchCancelled: Boolean,
    val prefetchStartedBeforeDemand: Boolean,
    val extractMs: Long? = null,
    val imageRenderMs: Long? = null,
)

fun formatFirstImageAnalysis(timing: FirstImageTiming): String {
    return "analysis first_image page=${timing.page} " +
        "totalMs=${timing.totalMs} " +
        "likelyCause=${firstImageLikelyCause(timing)} " +
        "remoteOpenMs=${timing.remoteOpenMs.formatDiagnosticMs()} " +
        "sessionInitialPageMs=${timing.sessionInitialPageMs.formatDiagnosticMs()} " +
        "pageExtractMs=${timing.pageExtractMs.formatDiagnosticMs()} " +
        "imageRenderMs=${timing.imageRenderMs.formatDiagnosticMs()} " +
        "cacheHit=${timing.cacheHit}"
}

fun formatPageNotReadyAnalysis(timing: PageNotReadyTiming): String {
    return "analysis page_not_ready page=${timing.page} " +
        "waitMs=${timing.waitMs} " +
        "likelyCause=${pageNotReadyLikelyCause(timing)} " +
        "wasPrefetchPlanned=${timing.wasPrefetchPlanned} " +
        "wasPrefetchCancelled=${timing.wasPrefetchCancelled} " +
        "prefetchStartedBeforeDemand=${timing.prefetchStartedBeforeDemand} " +
        "extractMs=${timing.extractMs.formatDiagnosticMs()} " +
        "imageRenderMs=${timing.imageRenderMs.formatDiagnosticMs()}"
}

private fun firstImageLikelyCause(timing: FirstImageTiming): String {
    return listOf(
        "remote_open" to timing.remoteOpenMs,
        "session_initial_page" to timing.sessionInitialPageMs,
        "page_extract" to timing.pageExtractMs,
        "image_decode" to timing.imageRenderMs,
    )
        .mapNotNull { (cause, duration) -> duration?.let { cause to it } }
        .maxByOrNull { (_, duration) -> duration }
        ?.takeIf { (_, duration) -> duration > 0L }
        ?.first
        ?: "unknown"
}

private fun pageNotReadyLikelyCause(timing: PageNotReadyTiming): String {
    if (!timing.wasPrefetchPlanned) return "not_prefetched"
    if (timing.wasPrefetchCancelled) return "prefetch_cancelled"
    if (!timing.prefetchStartedBeforeDemand) return "prefetch_too_late"

    val extractMs = timing.extractMs ?: 0L
    val imageRenderMs = timing.imageRenderMs ?: 0L
    return when {
        extractMs <= 0L && imageRenderMs <= 0L -> "unknown"
        extractMs >= imageRenderMs -> "extract_slow"
        else -> "image_decode_slow"
    }
}

private fun Long?.formatDiagnosticMs(): String {
    return this?.toString() ?: "unknown"
}
