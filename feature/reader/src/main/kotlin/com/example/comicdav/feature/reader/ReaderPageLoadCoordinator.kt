package com.example.comicdav.feature.reader

import com.example.comicdav.core.diagnostics.DiagnosticCategory
import com.example.comicdav.core.diagnostics.Diagnostics
import com.example.comicdav.core.diagnostics.NoopDiagnostics
import com.example.comicdav.core.ports.ComicReaderSession
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

internal data class ReaderPageLoadContext(
    val generation: Int,
    val cacheDir: File,
    val pageCacheKey: String,
    val transientPageKey: String,
)

internal fun ReaderSessionDescriptor.pageLoadContext(): ReaderPageLoadContext =
    ReaderPageLoadContext(
        generation = generation,
        cacheDir = cacheDir,
        pageCacheKey = pageCacheKey,
        transientPageKey = transientPageKey,
    )

/** Serializes page extraction and owns the page-image cache policy. */
internal class ReaderPageLoadCoordinator(
    private val ioDispatcher: CoroutineDispatcher,
    private val sessionGate: ReaderSessionGenerationGate,
    private val diagnostics: ReaderDiagnosticsTracker,
    private val elapsedRealtimeMs: () -> Long,
    private val prunePageCache: (cacheDir: File, protectedFile: File, maxBytes: Long) -> Unit,
    private val diagnosticLog: Diagnostics = NoopDiagnostics,
) {
    @Volatile
    private var pageCacheMaxBytes: Long = ReaderPageCache.DEFAULT_MAX_BYTES

    @Volatile
    private var pageImageCacheEnabled: Boolean = true

    fun updatePageCacheMaxBytes(maxBytes: Long) {
        pageCacheMaxBytes = maxBytes.coerceAtLeast(0L)
    }

    fun updatePageImageCacheEnabled(enabled: Boolean) {
        pageImageCacheEnabled = enabled
    }

    suspend fun loadPages(
        session: ComicReaderSession,
        context: ReaderPageLoadContext,
        pageIndexes: List<Int>,
        reason: String,
    ): Map<Int, File> = withContext(ioDispatcher) {
        val files = linkedMapOf<Int, File>()
        pageIndexes
            .distinct()
            .filter { it in 0 until session.pageCount }
            .forEach { index ->
                currentCoroutineContext().ensureActive()
                ensureCurrent(context.generation)
                val loadStartedAtMs = elapsedRealtimeMs()
                var pageCacheFileToPrune: File? = null
                val coroutineContext = currentCoroutineContext()
                val output = sessionGate.withSessionLock {
                    coroutineContext.ensureActive()
                    ensureCurrent(context.generation)
                    val cacheEnabled = pageImageCacheEnabled
                    val outputFile = if (cacheEnabled) {
                        ReaderPageCache.pageFile(context.cacheDir, context.pageCacheKey, index)
                    } else {
                        ReaderPageCache.transientPageFile(
                            cacheDir = context.cacheDir,
                            readerKey = context.transientPageKey,
                            pageIndex = index,
                        )
                    }
                    if (cacheEnabled && outputFile.isFile && outputFile.length() > 0L) {
                        outputFile.setLastModified(System.currentTimeMillis())
                        val durationMs = (elapsedRealtimeMs() - loadStartedAtMs).coerceAtLeast(0L)
                        diagnosticLog.detail(DiagnosticCategory.PAGE_LOAD) {
                            "load_page_cache_hit page=$index reason=$reason " +
                                "durationMs=$durationMs fileSize=${outputFile.length()}"
                        }
                        diagnostics.recordPageLoadTiming(
                            pageIndex = index,
                            reason = reason,
                            cacheHit = true,
                            loadStartedAtMs = loadStartedAtMs,
                            fileReadyAtMs = elapsedRealtimeMs(),
                            extractMs = 0L,
                            fileSize = outputFile.length(),
                        )
                        outputFile
                    } else {
                        val extractStartedAtMs = elapsedRealtimeMs()
                        diagnosticLog.detail(DiagnosticCategory.PAGE_LOAD) {
                            "load_page_extract_start page=$index reason=$reason"
                        }
                        val loadedFile = session.loadPageToFile(index, outputFile)
                        loadedFile.setLastModified(System.currentTimeMillis())
                        val readyAtMs = elapsedRealtimeMs()
                        val extractMs = (readyAtMs - extractStartedAtMs).coerceAtLeast(0L)
                        val durationMs = (readyAtMs - loadStartedAtMs).coerceAtLeast(0L)
                        diagnosticLog.detail(DiagnosticCategory.PAGE_LOAD) {
                            "load_page_extract_done page=$index reason=$reason " +
                                "durationMs=$durationMs extractMs=$extractMs fileSize=${loadedFile.length()}"
                        }
                        diagnostics.recordPageLoadTiming(
                            pageIndex = index,
                            reason = reason,
                            cacheHit = false,
                            loadStartedAtMs = loadStartedAtMs,
                            fileReadyAtMs = readyAtMs,
                            extractMs = extractMs,
                            fileSize = loadedFile.length(),
                        )
                        if (cacheEnabled) {
                            pageCacheFileToPrune = loadedFile
                        }
                        loadedFile
                    }
                }
                pageCacheFileToPrune?.let {
                    prunePageCache(context.cacheDir, it, pageCacheMaxBytes)
                }
                files[index] = output
            }
        files
    }

    private fun ensureCurrent(expectedGeneration: Int) {
        if (!sessionGate.isCurrent(expectedGeneration)) {
            throw CancellationException("reader session changed")
        }
    }
}
