package com.example.comicdav.feature.reader

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import com.example.comicdav.core.diagnostics.DiagnosticSink
import com.example.comicdav.core.diagnostics.diagnosticId
import com.example.comicdav.core.diagnostics.formatDiagnosticLine
import com.example.comicdav.core.diagnostics.formatDiagnosticThrowable
import com.example.comicdav.core.diagnostics.redactDiagnosticText
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

class ContentUriReaderLogSink(
    context: Context,
    private val uri: Uri,
    private val scope: CoroutineScope,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) : DiagnosticSink {
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
    val sink: DiagnosticSink,
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
    return formatDiagnosticLine(event, now)
}

fun formatThrowable(event: String, error: Throwable): String = formatDiagnosticThrowable(event, error)

fun redactReaderLogText(text: String): String = redactDiagnosticText(text)

fun readerLogId(prefix: String, raw: String): String = diagnosticId(prefix, raw)

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
    val queueOrWaitMs: Long? = null,
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
        "queueOrWaitMs=${timing.queueOrWaitMs.formatDiagnosticMs()} " +
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

    val queueOrWaitMs = timing.queueOrWaitMs ?: 0L
    val extractMs = timing.extractMs ?: 0L
    val imageRenderMs = timing.imageRenderMs ?: 0L
    return listOf(
        "queue_or_wait" to queueOrWaitMs,
        "extract_slow" to extractMs,
        "image_decode_slow" to imageRenderMs,
    )
        .maxByOrNull { (_, duration) -> duration }
        ?.takeIf { (_, duration) -> duration > 0L }
        ?.first
        ?: "unknown"
}

private fun Long?.formatDiagnosticMs(): String {
    return this?.toString() ?: "unknown"
}
