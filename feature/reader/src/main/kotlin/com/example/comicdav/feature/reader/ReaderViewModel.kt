package com.example.comicdav.feature.reader

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.comicdav.core.diagnostics.Diagnostics
import com.example.comicdav.core.diagnostics.NoopDiagnostics
import com.example.comicdav.core.model.history.WatchHistoryEntry
import com.example.comicdav.core.model.history.WatchHistoryMetadata
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
typealias RecordReadingHistory = suspend (entry: WatchHistoryEntry) -> Unit

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
    private val recordHistory: RecordReadingHistory = {},
    private val diagnosticLog: Diagnostics = NoopDiagnostics,
    prunePageCache: (cacheDir: File, protectedFile: File, maxBytes: Long) -> Unit = { cacheDir, protectedFile, maxBytes ->
        ReaderPageCache.prune(cacheDir, protectedFile, maxBytes)
    },
) : ViewModel() {
    var uiState by mutableStateOf(ReaderUiState())
        private set

    private var historyMetadata: WatchHistoryMetadata? = null
    private val sessionCoordinator = ReaderSessionCoordinator(
        ioDispatcher = ioDispatcher,
    )
    private val pageLoadCoordinator = ReaderPageLoadCoordinator(
        ioDispatcher = ioDispatcher,
        sessionGate = sessionCoordinator,
        prunePageCache = prunePageCache,
    )
    private val prefetchCoordinator = ReaderPrefetchCoordinator(
        scope = viewModelScope,
        ioDispatcher = ioDispatcher,
        sessionCoordinator = sessionCoordinator,
        pageLoadCoordinator = pageLoadCoordinator,
        diagnosticLog = diagnosticLog,
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

    fun openLocal(
        path: String,
        cacheDir: File,
        initialPage: Int = 0,
        comicKey: String? = null,
        historyMetadata: WatchHistoryMetadata? = null,
    ) {
        closeCurrentSession()
        this.historyMetadata = historyMetadata
        val pageCacheKey = comicKey ?: "local-${path.hashCode()}"
        val opening = requireNotNull(
            sessionCoordinator.beginOpening(
                cacheDir = cacheDir,
                comicKey = comicKey,
                pageCacheKey = pageCacheKey,
                readerKeyBase = pageCacheKey,
            ),
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
                    prefetchCoordinator.updateViewport(opened.session, opened.currentPage, opening.generation)
                    prefetchCoordinator.prefetchNeighbors(opened.currentPage)
                    saveHistory(opened.currentPage, opened.session.pageCount)
                },
                onFailure = { error ->
                    diagnosticLog.error("open_local_failed", error)
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
        historyMetadata: WatchHistoryMetadata? = null,
    ) {
        closeCurrentSession()
        this.historyMetadata = historyMetadata
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
                    prefetchCoordinator.updateViewport(opened.session, opened.currentPage, openGeneration)
                    prefetchCoordinator.prefetchNeighbors(opened.currentPage)
                    saveHistory(opened.currentPage, opened.session.pageCount)
                },
                onFailure = { error ->
                    sessionCoordinator.closeSessionAsync(openedSession)
                    diagnosticLog.error("open_session_failed", error)
                    uiState = ReaderUiState(error = error.message ?: "打开漫画失败")
                },
            )
        }
    }

    fun openRemote(
        cacheDir: File,
        historyMetadata: WatchHistoryMetadata? = null,
        openComic: suspend () -> OpenComicResult,
    ) {
        closeCurrentSession()
        this.historyMetadata = historyMetadata
        val openGeneration = sessionCoordinator.generation
        uiState = ReaderUiState(isLoading = true)
        val job = viewModelScope.launch {
            runCatching {
                openComic()
            }.fold(
                onSuccess = { result ->
                    if (!sessionCoordinator.isCurrent(openGeneration)) {
                        sessionCoordinator.closeSessionAsync(result.session)
                        return@fold
                    }
                    sessionCoordinator.clearRemoteOpen(currentCoroutineContext()[Job])
                    this@ReaderViewModel.historyMetadata =
                        this@ReaderViewModel.historyMetadata?.copy(mediaKey = result.comicKey)
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
                    diagnosticLog.error("open_remote_failed", error)
                    uiState = ReaderUiState(error = error.message ?: "打开远程漫画失败")
                },
            )
        }
        sessionCoordinator.trackRemoteOpen(job)
    }

    fun selectPage(pageIndex: Int) {
        val activeReader = sessionCoordinator.activeSession
        if (activeReader == null) {
            return
        }
        val activeSession = activeReader.session
        val opening = activeReader.descriptor
        if (pageIndex !in 0 until activeSession.pageCount) {
            return
        }

        reportPageDemand(pageIndex, "select_page")
        val existingFile = uiState.pageFiles[pageIndex]
        uiState = uiState.copy(
            currentPage = pageIndex,
            isLoading = existingFile == null,
            error = null,
        )
        if (existingFile != null) {
            prefetchCoordinator.updateViewport(activeSession, pageIndex, opening.generation)
            saveProgress(pageIndex)
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
                    prefetchCoordinator.prefetchNeighbors(pageIndex)
                },
                onFailure = { error ->
                    if (!sessionCoordinator.isCurrent(loadGeneration)) return@fold
                    diagnosticLog.error("select_page_load_failed page=$pageIndex", error)
                    uiState = uiState.copy(
                        isLoading = false,
                        error = error.message ?: "加载页面失败",
                    )
                },
            )
        }
    }

    fun closeReader() {
        closeCurrentSession()
        uiState = ReaderUiState()
    }

    override fun onCleared() {
        closeCurrentSession()
        prefetchCoordinator.shutdown()
    }

    private fun closeCurrentSession() {
        sessionCoordinator.closeCurrentSession(
            cancelDependentWork = {
                prefetchCoordinator.cancelSessionWork(uiState.currentPage)
            },
        )
    }

    fun reportPageDemand(pageIndex: Int, source: String) {
        val ready = uiState.pageFiles[pageIndex] != null
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
    }

    private fun String.shouldAdvancePrefetchOnDemand(): Boolean =
        this == "pager_current" || this == "pager_target" || this == "continuous_visible"

    private fun saveProgress(pageIndex: Int) {
        sessionCoordinator.currentComicKey?.let { key ->
            viewModelScope.launch {
                runCatching {
                    savePage(key, pageIndex)
                    saveHistory(pageIndex, uiState.pageCount)
                }.onFailure { error ->
                    diagnosticLog.error("save_progress_failed page=$pageIndex key=$key", error)
                }
            }
        }
    }

    private suspend fun saveHistory(pageIndex: Int, pageCount: Int) {
        val metadata = historyMetadata ?: return
        recordHistory(
            metadata.entry(
                progress = (pageIndex + 1).toLong(),
                total = pageCount.toLong(),
            ),
        )
    }

    private data class OpenedReader(
        val session: ComicReaderSession,
        val currentPage: Int,
        val files: Map<Int, File>,
    )

    private companion object {
        const val LOAD_REASON_INITIAL = "initial"
        const val LOAD_REASON_SELECT = "select"
    }
}
