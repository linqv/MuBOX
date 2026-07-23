package com.example.comicdav.feature.reader

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.comicdav.core.diagnostics.DiagnosticCategory
import com.example.comicdav.core.ports.ComicReaderSession
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

typealias ComicSessionFactory = (path: String) -> ComicReaderSession
typealias SaveReadingProgress = suspend (comicKey: String, pageIndex: Int) -> Unit

data class ReaderUiState(
    val pageCount: Int = 0,
    val currentPage: Int = 0,
    val pageFiles: Map<Int, File> = emptyMap(),
    val isLoading: Boolean = false,
    val error: String? = null,
    val readerKey: String? = null,
)

class ReaderViewModel(
    private val openSession: ComicSessionFactory = { _ ->
        error("ReaderViewModel requires a local comic session factory")
    },
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val savePage: SaveReadingProgress = { _, _ -> },
    prunePageCache: (cacheDir: File, protectedFile: File, maxBytes: Long) -> Unit = { cacheDir, protectedFile, maxBytes ->
        ReaderPageCache.prune(cacheDir, protectedFile, maxBytes)
    },
    private val elapsedRealtimeMs: () -> Long = { System.nanoTime() / 1_000_000L },
) : ViewModel() {
    var uiState by mutableStateOf(ReaderUiState())
        private set

    private val diagnostics = ReaderDiagnosticsTracker(elapsedRealtimeMs)
    private val sessionCoordinator = ReaderSessionCoordinator(ioDispatcher)
    private val pageLoadCoordinator = ReaderPageLoadCoordinator(
        ioDispatcher = ioDispatcher,
        sessionGate = sessionCoordinator,
        diagnostics = diagnostics,
        elapsedRealtimeMs = elapsedRealtimeMs,
        prunePageCache = prunePageCache,
    )
    private val prefetchCoordinator = ReaderPrefetchCoordinator(
        scope = viewModelScope,
        ioDispatcher = ioDispatcher,
        sessionCoordinator = sessionCoordinator,
        pageLoadCoordinator = pageLoadCoordinator,
        diagnostics = diagnostics,
        pageFiles = { uiState.pageFiles },
        onPageFilesLoaded = { files ->
            uiState = uiState.copy(pageFiles = uiState.pageFiles + files)
        },
    )

    fun updatePageCacheMaxBytes(maxBytes: Long) {
        pageLoadCoordinator.updatePageCacheMaxBytes(maxBytes)
    }

    fun updatePageImageCacheEnabled(enabled: Boolean) {
        pageLoadCoordinator.updatePageImageCacheEnabled(enabled)
    }

    fun openLocal(path: String, cacheDir: File, initialPage: Int = 0, comicKey: String? = null) {
        closeCurrentSession()
        diagnostics.reset()
        val pageCacheKey = comicKey ?: "local-${path.hashCode()}"
        val opening = requireNotNull(
            sessionCoordinator.beginOpening(
                cacheDir = cacheDir,
                comicKey = comicKey,
                pageCacheKey = pageCacheKey,
                readerKeyBase = pageCacheKey,
            ),
        )
        ReaderDiagnosticLog.event(
            "open_local_start initialPage=$initialPage generation=${opening.generation} key=${opening.readerKey}",
        )
        uiState = ReaderUiState(isLoading = true, readerKey = opening.readerKey)
        viewModelScope.launch {
            runCatching {
                withContext(ioDispatcher) {
                    val openedSession = openSession(path)
                    val startPage = initialPage.coerceIn(0, (openedSession.pageCount - 1).coerceAtLeast(0))
                    val files = pageLoadCoordinator.loadPages(
                        session = openedSession,
                        context = opening.pageLoadContext(),
                        pageIndexes = listOf(startPage),
                        reason = LOAD_REASON_INITIAL,
                    )
                    OpenedReader(openedSession, startPage, files)
                }
            }.fold(
                onSuccess = { opened ->
                    if (!sessionCoordinator.activate(opening, opened.session)) return@fold
                    uiState = ReaderUiState(
                        pageCount = opened.session.pageCount,
                        currentPage = opened.currentPage,
                        pageFiles = opened.files,
                        readerKey = opening.readerKey,
                    )
                    ReaderDiagnosticLog.event(
                        "open_local_success pageCount=${opened.session.pageCount} current=${opened.currentPage} files=${opened.files.keys.sorted()}",
                    )
                    prefetchCoordinator.updateViewport(opened.session, opened.currentPage, opening.generation)
                    prefetchCoordinator.prefetchNeighbors(opened.currentPage)
                },
                onFailure = { error ->
                    ReaderDiagnosticLog.error("open_local_failed", error)
                    uiState = ReaderUiState(error = error.message ?: "打开漫画失败")
                },
            )
        }
    }

    fun openExistingSession(
        openedSession: ComicReaderSession,
        cacheDir: File,
        initialPage: Int,
        comicKey: String,
        pageCacheKey: String = comicKey,
    ) {
        closeCurrentSession()
        diagnostics.reset()
        startOpenedSession(
            openedSession = openedSession,
            cacheDir = cacheDir,
            initialPage = initialPage,
            comicKey = comicKey,
            pageCacheKey = pageCacheKey,
            openGeneration = sessionCoordinator.generation,
        )
    }

    private fun startOpenedSession(
        openedSession: ComicReaderSession,
        cacheDir: File,
        initialPage: Int,
        comicKey: String,
        pageCacheKey: String,
        openGeneration: Int,
    ) {
        val opening = sessionCoordinator.beginOpening(
            cacheDir = cacheDir,
            comicKey = comicKey,
            pageCacheKey = pageCacheKey,
            readerKeyBase = comicKey,
            expectedGeneration = openGeneration,
        )
        if (opening == null) {
            sessionCoordinator.closeSessionAsync(openedSession)
            return
        }
        ReaderDiagnosticLog.event("open_session_start initialPage=$initialPage generation=$openGeneration key=$comicKey")
        uiState = ReaderUiState(isLoading = true, readerKey = opening.readerKey)
        viewModelScope.launch {
            runCatching {
                val startPage = initialPage.coerceIn(0, (openedSession.pageCount - 1).coerceAtLeast(0))
                val files = pageLoadCoordinator.loadPages(
                    session = openedSession,
                    context = opening.pageLoadContext(),
                    pageIndexes = listOf(startPage),
                    reason = LOAD_REASON_INITIAL,
                )
                OpenedReader(openedSession, startPage, files)
            }.fold(
                onSuccess = { opened ->
                    if (!sessionCoordinator.activate(opening, opened.session)) return@fold
                    uiState = ReaderUiState(
                        pageCount = opened.session.pageCount,
                        currentPage = opened.currentPage,
                        pageFiles = opened.files,
                        readerKey = opening.readerKey,
                    )
                    ReaderDiagnosticLog.event(
                        "open_session_success pageCount=${opened.session.pageCount} current=${opened.currentPage} files=${opened.files.keys.sorted()}",
                    )
                    prefetchCoordinator.updateViewport(opened.session, opened.currentPage, openGeneration)
                    prefetchCoordinator.prefetchNeighbors(opened.currentPage)
                },
                onFailure = { error ->
                    sessionCoordinator.closeSessionAsync(openedSession)
                    ReaderDiagnosticLog.error("open_session_failed", error)
                    uiState = ReaderUiState(error = error.message ?: "打开漫画失败")
                },
            )
        }
    }

    fun openRemote(
        cacheDir: File,
        openComic: suspend () -> OpenComicResult,
    ) {
        closeCurrentSession()
        diagnostics.reset()
        val openGeneration = sessionCoordinator.generation
        val remoteOpenStartedAtMs = elapsedRealtimeMs()
        ReaderDiagnosticLog.event("open_remote_start generation=$openGeneration")
        uiState = ReaderUiState(isLoading = true)
        val job = viewModelScope.launch {
            runCatching {
                openComic()
            }.fold(
                onSuccess = { result ->
                    if (!sessionCoordinator.isCurrent(openGeneration)) {
                        ReaderDiagnosticLog.event(
                            "open_remote_stale_result generation=$openGeneration active=${sessionCoordinator.generation}",
                        )
                        sessionCoordinator.closeSessionAsync(result.session)
                        return@fold
                    }
                    sessionCoordinator.clearRemoteOpen(currentCoroutineContext()[Job])
                    val remoteDurationMs = (elapsedRealtimeMs() - remoteOpenStartedAtMs).coerceAtLeast(0L)
                    diagnostics.recordRemoteOpenDuration(remoteDurationMs)
                    ReaderDiagnosticLog.event(
                        "open_remote_result key=${result.comicKey} initialPage=${result.initialPage} " +
                            "fileSize=${result.localFile.length()} durationMs=$remoteDurationMs",
                    )
                    startOpenedSession(
                        openedSession = result.session,
                        cacheDir = cacheDir,
                        initialPage = result.initialPage,
                        comicKey = result.comicKey,
                        pageCacheKey = result.pageCacheKey,
                        openGeneration = openGeneration,
                    )
                },
                onFailure = { error ->
                    if (!sessionCoordinator.isCurrent(openGeneration) || error is CancellationException) return@fold
                    ReaderDiagnosticLog.error("open_remote_failed", error)
                    uiState = ReaderUiState(error = error.message ?: "打开远程漫画失败")
                },
            )
        }
        sessionCoordinator.trackRemoteOpen(job)
    }

    fun selectPage(pageIndex: Int) {
        val activeReader = sessionCoordinator.activeSession
        if (activeReader == null) {
            ReaderDiagnosticLog.event("select_page_ignored_no_session page=$pageIndex")
            return
        }
        val activeSession = activeReader.session
        val opening = activeReader.descriptor
        if (pageIndex !in 0 until activeSession.pageCount) {
            ReaderDiagnosticLog.event("select_page_ignored_out_of_range page=$pageIndex pageCount=${activeSession.pageCount}")
            return
        }

        reportPageDemand(pageIndex, "select_page")
        val existingFile = uiState.pageFiles[pageIndex]
        ReaderDiagnosticLog.event(
            "select_page page=$pageIndex previous=${uiState.currentPage} cached=${existingFile != null} pageCount=${activeSession.pageCount}",
        )
        uiState = uiState.copy(
            currentPage = pageIndex,
            isLoading = existingFile == null,
            error = null,
        )
        if (existingFile != null) {
            prefetchCoordinator.updateViewport(activeSession, pageIndex, opening.generation)
            saveProgress(pageIndex)
            ReaderDiagnosticLog.event("select_page_cached page=$pageIndex fileSize=${existingFile.length()}")
            prefetchCoordinator.prefetchNeighbors(pageIndex, reason = "select_page")
            return
        }
        val loadGeneration = opening.generation
        prefetchCoordinator.prioritizeSelectedPageLoad(pageIndex)
        viewModelScope.launch {
            runCatching {
                pageLoadCoordinator.loadPages(
                    session = activeSession,
                    context = opening.pageLoadContext(),
                    pageIndexes = listOf(pageIndex),
                    reason = LOAD_REASON_SELECT,
                )
            }.fold(
                onSuccess = { files ->
                    if (!sessionCoordinator.isCurrent(loadGeneration)) return@fold
                    uiState = uiState.copy(
                        currentPage = pageIndex,
                        pageFiles = uiState.pageFiles + files,
                        isLoading = false,
                    )
                    prefetchCoordinator.updateViewport(activeSession, pageIndex, loadGeneration)
                    saveProgress(pageIndex)
                    ReaderDiagnosticLog.event("select_page_loaded page=$pageIndex files=${files.keys.sorted()}")
                    prefetchCoordinator.prefetchNeighbors(pageIndex)
                },
                onFailure = { error ->
                    if (!sessionCoordinator.isCurrent(loadGeneration)) return@fold
                    ReaderDiagnosticLog.error("select_page_load_failed page=$pageIndex", error)
                    uiState = uiState.copy(
                        isLoading = false,
                        error = error.message ?: "加载页面失败",
                    )
                },
            )
        }
    }

    fun closeReader() {
        ReaderDiagnosticLog.event("close_reader")
        closeCurrentSession()
        uiState = ReaderUiState()
    }

    override fun onCleared() {
        closeCurrentSession()
        prefetchCoordinator.shutdown()
    }

    private fun closeCurrentSession() {
        sessionCoordinator.closeCurrentSession(
            beforeRemoteCancellation = { activeSession ->
                if (activeSession != null) {
                    diagnostics.localSessionSummary()?.let { summary ->
                        ReaderDiagnosticLog.summary(DiagnosticCategory.SESSION) {
                            summary.formatLocalSessionSummary()
                        }
                    }
                }
            },
            cancelDependentWork = {
                prefetchCoordinator.cancelSessionWork(uiState.currentPage)
            },
        )
    }

    fun reportPageDemand(pageIndex: Int, source: String) {
        val ready = uiState.pageFiles[pageIndex] != null
        ReaderDiagnosticLog.detail(DiagnosticCategory.UI) {
            "page_demand page=$pageIndex source=$source ready=$ready"
        }
        if (pageIndex !in 0 until uiState.pageCount) return
        if (ready) {
            val activeReader = sessionCoordinator.activeSession
            if (activeReader?.session?.advancePrefetchOnPageDemand == true && source.shouldAdvancePrefetchOnDemand()) {
                prefetchCoordinator.updateViewport(activeReader.session, pageIndex, activeReader.descriptor.generation)
                prefetchCoordinator.prefetchNeighbors(pageIndex, reason = source)
            }
            return
        }
        if (source == "pager_target") {
            prefetchCoordinator.prefetchDemandRanges(pageIndex, source)
        }

        diagnostics.recordPageDemand(pageIndex, source)
    }

    private fun String.shouldAdvancePrefetchOnDemand(): Boolean =
        this == "pager_current" || this == "pager_target" || this == "continuous_visible"

    fun reportImageLoadStarted(pageIndex: Int) {
        diagnostics.recordImageLoadStarted(pageIndex)
        ReaderDiagnosticLog.detail(DiagnosticCategory.IMAGE) { "image_load_start page=$pageIndex" }
    }

    fun reportImageLoadSucceeded(pageIndex: Int) {
        val completedAtMs = elapsedRealtimeMs()
        val imageRenderMs = diagnostics.imageRenderDuration(pageIndex, completedAtMs)
        ReaderDiagnosticLog.detail(DiagnosticCategory.IMAGE) {
            "image_load_success page=$pageIndex durationMs=${imageRenderMs ?: "unknown"}"
        }
        diagnostics.firstImageAnalysisIfNeeded(pageIndex, completedAtMs, imageRenderMs)
            ?.let(ReaderDiagnosticLog::event)
        diagnostics.pageNotReadyAnalysisIfNeeded(pageIndex, completedAtMs, imageRenderMs)
            ?.let(ReaderDiagnosticLog::event)
    }

    fun reportImageLoadFailed(pageIndex: Int) {
        ReaderDiagnosticLog.detail(DiagnosticCategory.IMAGE) { "image_load_failed page=$pageIndex" }
    }

    private fun saveProgress(pageIndex: Int) {
        sessionCoordinator.currentComicKey?.let { key ->
            viewModelScope.launch {
                runCatching {
                    savePage(key, pageIndex)
                }.onFailure { error ->
                    ReaderDiagnosticLog.error("save_progress_failed page=$pageIndex key=$key", error)
                }
            }
        }
    }

    private data class OpenedReader(
        val session: ComicReaderSession,
        val currentPage: Int,
        val files: Map<Int, File>,
    )

    private fun LocalSessionPerformanceSummary.formatLocalSessionSummary(): String =
        "local_session_summary pagesLoaded=$pagesLoaded " +
            "cacheHits=$cacheHits cacheMisses=$cacheMisses " +
            "totalOutputBytes=$totalOutputBytes largestOutputBytes=$largestOutputBytes " +
            "slowestPage=$slowestPage slowestPageMs=$slowestPageMs " +
            "slowestPageExtractMs=$slowestPageExtractMs " +
            "slowestPageQueueOrWaitMs=$slowestPageQueueOrWaitMs " +
            "slowestPageReason=$slowestPageReason"

    private companion object {
        const val LOAD_REASON_INITIAL = "initial"
        const val LOAD_REASON_SELECT = "select"
    }
}
