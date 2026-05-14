package com.example.comicdav.feature.reader

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.comicdav.nativebridge.ComicEngine
import com.example.comicdav.nativebridge.ComicReaderSession
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

typealias ComicSessionFactory = (path: String) -> ComicReaderSession
typealias SaveReadingProgress = suspend (comicKey: String, pageIndex: Int) -> Unit

data class ReaderUiState(
    val pageCount: Int = 0,
    val currentPage: Int = 0,
    val pageFiles: Map<Int, File> = emptyMap(),
    val isLoading: Boolean = false,
    val error: String? = null,
)

class ReaderViewModel(
    private val openSession: ComicSessionFactory = { path -> ComicEngine().openLocal(path) },
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val savePage: SaveReadingProgress = { _, _ -> },
    private val elapsedRealtimeMs: () -> Long = { System.nanoTime() / 1_000_000L },
) : ViewModel() {
    var uiState by mutableStateOf(ReaderUiState())
        private set

    private var session: ComicReaderSession? = null
    private var cacheDir: File? = null
    private var comicKey: String? = null
    private var pageCacheKey: String? = null
    private var generation = 0
    private var remoteOpenJob: Job? = null
    private var prefetchJob: Job? = null
    private var viewportJob: Job? = null
    private val sessionMutex = Mutex()
    private val cleanupScope = CoroutineScope(SupervisorJob() + ioDispatcher)
    private val diagnosticLock = Any()
    private val pageLoadTimings = mutableMapOf<Int, PageLoadDiagnostic>()
    private val prefetchDiagnostics = mutableMapOf<Int, PrefetchDiagnostic>()
    private val pageWaits = mutableMapOf<Int, PageWaitDiagnostic>()
    private val imageLoadStarts = mutableMapOf<Int, Long>()
    private var readerOpenStartedAtMs: Long? = null
    private var remoteOpenDurationMs: Long? = null
    private var sessionInitialPageMs: Long? = null
    private var firstImageAnalysisLogged = false

    fun openLocal(path: String, cacheDir: File, initialPage: Int = 0, comicKey: String? = null) {
        closeCurrentSession()
        resetReaderDiagnostics()
        this.cacheDir = cacheDir
        this.comicKey = comicKey
        this.pageCacheKey = comicKey ?: "local-${path.hashCode()}"
        val openGeneration = generation
        ReaderDiagnosticLog.event("open_local_start initialPage=$initialPage generation=$openGeneration key=${this.pageCacheKey}")
        uiState = ReaderUiState(isLoading = true)
        viewModelScope.launch {
            runCatching {
                withContext(ioDispatcher) {
                    val openedSession = openSession(path)
                    val startPage = initialPage.coerceIn(0, (openedSession.pageCount - 1).coerceAtLeast(0))
                    val files = loadPages(
                        session = openedSession,
                        pageIndexes = listOf(startPage),
                        cacheDir = cacheDir,
                        expectedGeneration = openGeneration,
                        reason = LOAD_REASON_INITIAL,
                    )
                    OpenedReader(openedSession, startPage, files)
                }
            }.fold(
                onSuccess = { opened ->
                    if (openGeneration != generation) {
                        closeSessionAsync(opened.session)
                        return@fold
                    }
                    session = opened.session
                    uiState = ReaderUiState(
                        pageCount = opened.session.pageCount,
                        currentPage = opened.currentPage,
                        pageFiles = opened.files,
                    )
                    ReaderDiagnosticLog.event(
                        "open_local_success pageCount=${opened.session.pageCount} current=${opened.currentPage} files=${opened.files.keys.sorted()}",
                    )
                    scheduleViewportUpdate(opened.session, opened.currentPage, openGeneration)
                    prefetchNeighbors(opened.currentPage)
                },
                onFailure = { error ->
                    ReaderDiagnosticLog.error("open_local_failed", error)
                    uiState = ReaderUiState(error = error.message ?: "Failed to open comic")
                },
            )
        }
    }

    fun openExistingSession(
        openedSession: ComicReaderSession,
        cacheDir: File,
        initialPage: Int,
        comicKey: String,
    ) {
        closeCurrentSession()
        resetReaderDiagnostics()
        startOpenedSession(
            openedSession = openedSession,
            cacheDir = cacheDir,
            initialPage = initialPage,
            comicKey = comicKey,
            openGeneration = generation,
        )
    }

    private fun startOpenedSession(
        openedSession: ComicReaderSession,
        cacheDir: File,
        initialPage: Int,
        comicKey: String,
        openGeneration: Int,
    ) {
        this.cacheDir = cacheDir
        this.comicKey = comicKey
        this.pageCacheKey = comicKey
        ReaderDiagnosticLog.event("open_session_start initialPage=$initialPage generation=$openGeneration key=$comicKey")
        uiState = ReaderUiState(isLoading = true)
        viewModelScope.launch {
            runCatching {
                withContext(ioDispatcher) {
                    val startPage = initialPage.coerceIn(0, (openedSession.pageCount - 1).coerceAtLeast(0))
                    val files = loadPages(
                        session = openedSession,
                        pageIndexes = listOf(startPage),
                        cacheDir = cacheDir,
                        expectedGeneration = openGeneration,
                        reason = LOAD_REASON_INITIAL,
                    )
                    OpenedReader(openedSession, startPage, files)
                }
            }.fold(
                onSuccess = { opened ->
                    if (openGeneration != generation) {
                        closeSessionAsync(opened.session)
                        return@fold
                    }
                    session = opened.session
                    uiState = ReaderUiState(
                        pageCount = opened.session.pageCount,
                        currentPage = opened.currentPage,
                        pageFiles = opened.files,
                    )
                    ReaderDiagnosticLog.event(
                        "open_session_success pageCount=${opened.session.pageCount} current=${opened.currentPage} files=${opened.files.keys.sorted()}",
                    )
                    scheduleViewportUpdate(opened.session, opened.currentPage, openGeneration)
                    prefetchNeighbors(opened.currentPage)
                },
                onFailure = { error ->
                    closeSessionAsync(openedSession)
                    ReaderDiagnosticLog.error("open_session_failed", error)
                    uiState = ReaderUiState(error = error.message ?: "Failed to open comic")
                },
            )
        }
    }

    fun openRemote(
        cacheDir: File,
        openComic: suspend () -> OpenComicResult,
    ) {
        closeCurrentSession()
        resetReaderDiagnostics()
        this.cacheDir = cacheDir
        val openGeneration = generation
        val remoteOpenStartedAtMs = elapsedRealtimeMs()
        ReaderDiagnosticLog.event("open_remote_start generation=$openGeneration")
        uiState = ReaderUiState(isLoading = true)
        remoteOpenJob = viewModelScope.launch {
            runCatching {
                openComic()
            }.fold(
                onSuccess = { result ->
                    if (openGeneration != generation) {
                        ReaderDiagnosticLog.event("open_remote_stale_result generation=$openGeneration active=$generation")
                        closeSessionAsync(result.session)
                        return@fold
                    }
                    remoteOpenJob = null
                    val remoteDurationMs = (elapsedRealtimeMs() - remoteOpenStartedAtMs).coerceAtLeast(0L)
                    synchronized(diagnosticLock) {
                        remoteOpenDurationMs = remoteDurationMs
                    }
                    ReaderDiagnosticLog.event(
                        "open_remote_result key=${result.comicKey} initialPage=${result.initialPage} " +
                            "fileSize=${result.localFile.length()} durationMs=$remoteDurationMs",
                    )
                    startOpenedSession(
                        openedSession = result.session,
                        cacheDir = cacheDir,
                        initialPage = result.initialPage,
                        comicKey = result.comicKey,
                        openGeneration = openGeneration,
                    )
                },
                onFailure = { error ->
                    if (openGeneration != generation || error is CancellationException) return@fold
                    ReaderDiagnosticLog.error("open_remote_failed", error)
                    uiState = ReaderUiState(error = error.message ?: "Failed to open remote comic")
                },
            )
        }
    }

    fun selectPage(pageIndex: Int) {
        val activeSession = session
        if (activeSession == null) {
            ReaderDiagnosticLog.event("select_page_ignored_no_session page=$pageIndex")
            return
        }
        val activeCacheDir = cacheDir
        if (activeCacheDir == null) {
            ReaderDiagnosticLog.event("select_page_ignored_no_cache page=$pageIndex")
            return
        }
        if (pageIndex !in 0 until activeSession.pageCount) {
            ReaderDiagnosticLog.event("select_page_ignored_out_of_range page=$pageIndex pageCount=${activeSession.pageCount}")
            return
        }

        reportPageDemand(pageIndex, "select_page")
        cancelActivePrefetch("select_page page=$pageIndex")
        prefetchJob?.cancel()
        prefetchJob = null
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
            scheduleViewportUpdate(activeSession, pageIndex, generation)
            saveProgress(pageIndex)
            ReaderDiagnosticLog.event("select_page_cached page=$pageIndex fileSize=${existingFile.length()}")
            prefetchNeighbors(pageIndex)
            return
        }
        val loadGeneration = generation
        viewModelScope.launch {
            runCatching {
                withContext(ioDispatcher) {
                    loadPages(
                        session = activeSession,
                        pageIndexes = listOf(pageIndex),
                        cacheDir = activeCacheDir,
                        expectedGeneration = loadGeneration,
                        reason = LOAD_REASON_SELECT,
                    )
                }
            }.fold(
                onSuccess = { files ->
                    if (loadGeneration != generation) return@fold
                    uiState = uiState.copy(
                        currentPage = pageIndex,
                        pageFiles = uiState.pageFiles + files,
                        isLoading = false,
                    )
                    scheduleViewportUpdate(activeSession, pageIndex, loadGeneration)
                    saveProgress(pageIndex)
                    ReaderDiagnosticLog.event("select_page_loaded page=$pageIndex files=${files.keys.sorted()}")
                    prefetchNeighbors(pageIndex)
                },
                onFailure = { error ->
                    if (loadGeneration != generation) return@fold
                    ReaderDiagnosticLog.error("select_page_load_failed page=$pageIndex", error)
                    uiState = uiState.copy(
                        isLoading = false,
                        error = error.message ?: "Failed to load page",
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
    }

    private fun closeCurrentSession() {
        ReaderDiagnosticLog.event("close_current_session generation=$generation hasSession=${session != null}")
        remoteOpenJob?.cancel()
        remoteOpenJob = null
        prefetchJob?.cancel()
        prefetchJob = null
        viewportJob?.cancel()
        viewportJob = null
        generation++
        session?.let(::closeSessionAsync)
        session = null
        comicKey = null
        pageCacheKey = null
    }

    fun reportPageDemand(pageIndex: Int, source: String) {
        val ready = uiState.pageFiles[pageIndex] != null
        ReaderDiagnosticLog.event("page_demand page=$pageIndex source=$source ready=$ready")
        if (ready || pageIndex !in 0 until uiState.pageCount) return

        val demandAtMs = elapsedRealtimeMs()
        synchronized(diagnosticLock) {
            if (pageWaits.containsKey(pageIndex)) return
            val prefetch = prefetchDiagnostics[pageIndex]
            pageWaits[pageIndex] = PageWaitDiagnostic(
                startedAtMs = demandAtMs,
                source = source,
                wasPrefetchPlanned = prefetch?.plannedAtMs != null && prefetch.plannedAtMs <= demandAtMs,
                prefetchStartedBeforeDemand = prefetch?.startedAtMs != null && prefetch.startedAtMs <= demandAtMs,
            )
        }
    }

    fun reportImageLoadStarted(pageIndex: Int) {
        val startedAtMs = elapsedRealtimeMs()
        synchronized(diagnosticLock) {
            imageLoadStarts[pageIndex] = startedAtMs
        }
        ReaderDiagnosticLog.event("image_load_start page=$pageIndex")
    }

    fun reportImageLoadSucceeded(pageIndex: Int) {
        val completedAtMs = elapsedRealtimeMs()
        val imageRenderMs = synchronized(diagnosticLock) {
            imageLoadStarts[pageIndex]?.let { (completedAtMs - it).coerceAtLeast(0L) }
        }
        ReaderDiagnosticLog.event("image_load_success page=$pageIndex durationMs=${imageRenderMs ?: "unknown"}")
        emitFirstImageAnalysisIfNeeded(pageIndex, completedAtMs, imageRenderMs)
        emitPageNotReadyAnalysisIfNeeded(pageIndex, completedAtMs, imageRenderMs)
    }

    fun reportImageLoadFailed(pageIndex: Int) {
        ReaderDiagnosticLog.event("image_load_failed page=$pageIndex")
    }

    private fun prefetchNeighbors(pageIndex: Int) {
        val activeSession = session ?: return
        val activeCacheDir = cacheDir ?: return
        val forwardPages = (1..PREFETCH_FORWARD_PAGES).map { pageIndex + it }
        val missingNeighbors = (forwardPages + (pageIndex - 1))
            .filter { it in 0 until activeSession.pageCount }
            .filterNot { uiState.pageFiles.containsKey(it) }
        if (missingNeighbors.isEmpty()) return

        cancelActivePrefetch("prefetch_restart current=$pageIndex")
        prefetchJob?.cancel()
        val prefetchGeneration = generation
        markPrefetchPlanned(missingNeighbors)
        ReaderDiagnosticLog.event("prefetch_start current=$pageIndex pages=$missingNeighbors generation=$prefetchGeneration")
        prefetchJob = viewModelScope.launch {
            delay(PREFETCH_START_DELAY_MS)
            for (page in missingNeighbors) {
                currentCoroutineContext().ensureActive()
                markPrefetchStarted(page)
                runCatching {
                    withContext(ioDispatcher) {
                        loadPages(
                            session = activeSession,
                            pageIndexes = listOf(page),
                            cacheDir = activeCacheDir,
                            expectedGeneration = prefetchGeneration,
                            reason = LOAD_REASON_PREFETCH,
                        )
                    }
                }.fold(
                    onSuccess = { files ->
                        currentCoroutineContext().ensureActive()
                        if (prefetchGeneration == generation) {
                            uiState = uiState.copy(pageFiles = uiState.pageFiles + files)
                            markPrefetchCompleted(page)
                            ReaderDiagnosticLog.event("prefetch_loaded page=$page files=${files.keys.sorted()}")
                        }
                    },
                    onFailure = { error ->
                        ReaderDiagnosticLog.error("prefetch_failed page=$page", error)
                        return@launch
                    },
                )
            }
        }
    }

    private fun saveProgress(pageIndex: Int) {
        comicKey?.let { key ->
            viewModelScope.launch {
                runCatching {
                    savePage(key, pageIndex)
                }.onFailure { error ->
                    ReaderDiagnosticLog.error("save_progress_failed page=$pageIndex key=$key", error)
                }
            }
        }
    }

    private fun scheduleViewportUpdate(session: ComicReaderSession, pageIndex: Int, expectedGeneration: Int) {
        viewportJob?.cancel()
        viewportJob = viewModelScope.launch {
            runCatching {
                withContext(ioDispatcher) {
                    sessionMutex.withLock {
                        if (expectedGeneration != generation) return@withLock
                        ReaderDiagnosticLog.event("update_viewport_start page=$pageIndex generation=$expectedGeneration")
                        session.updateViewport(pageIndex, NETWORK_WIFI)
                        ReaderDiagnosticLog.event("update_viewport_done page=$pageIndex generation=$expectedGeneration")
                    }
                }
            }.onFailure { error ->
                if (expectedGeneration == generation && error !is CancellationException) {
                    ReaderDiagnosticLog.error("update_viewport_failed page=$pageIndex", error)
                }
            }
        }
    }

    private suspend fun loadPages(
        session: ComicReaderSession,
        pageIndexes: List<Int>,
        cacheDir: File,
        expectedGeneration: Int,
        reason: String,
    ): Map<Int, File> {
        val files = linkedMapOf<Int, File>()
        pageIndexes
            .distinct()
            .filter { it in 0 until session.pageCount }
            .forEach { index ->
                currentCoroutineContext().ensureActive()
                if (expectedGeneration != generation) {
                    throw CancellationException("reader session changed")
                }
                val loadStartedAtMs = elapsedRealtimeMs()
                val output = sessionMutex.withLock {
                    currentCoroutineContext().ensureActive()
                    if (expectedGeneration != generation) {
                        throw CancellationException("reader session changed")
                    }
                    val outputFile = pageCacheFile(cacheDir, index)
                    if (outputFile.isFile && outputFile.length() > 0L) {
                        val durationMs = (elapsedRealtimeMs() - loadStartedAtMs).coerceAtLeast(0L)
                        ReaderDiagnosticLog.event(
                            "load_page_cache_hit page=$index reason=$reason " +
                                "durationMs=$durationMs fileSize=${outputFile.length()}",
                        )
                        recordPageLoadTiming(
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
                        ReaderDiagnosticLog.event("load_page_extract_start page=$index reason=$reason")
                        val loadedFile = session.loadPageToFile(index, outputFile)
                        val readyAtMs = elapsedRealtimeMs()
                        val extractMs = (readyAtMs - extractStartedAtMs).coerceAtLeast(0L)
                        val durationMs = (readyAtMs - loadStartedAtMs).coerceAtLeast(0L)
                        ReaderDiagnosticLog.event(
                            "load_page_extract_done page=$index reason=$reason " +
                                "durationMs=$durationMs extractMs=$extractMs fileSize=${loadedFile.length()}",
                        )
                        recordPageLoadTiming(
                            pageIndex = index,
                            reason = reason,
                            cacheHit = false,
                            loadStartedAtMs = loadStartedAtMs,
                            fileReadyAtMs = readyAtMs,
                            extractMs = extractMs,
                            fileSize = loadedFile.length(),
                        )
                        loadedFile
                    }
                }
                files[index] = output
        }
        return files
    }

    private fun resetReaderDiagnostics() {
        synchronized(diagnosticLock) {
            pageLoadTimings.clear()
            prefetchDiagnostics.clear()
            pageWaits.clear()
            imageLoadStarts.clear()
            readerOpenStartedAtMs = elapsedRealtimeMs()
            remoteOpenDurationMs = null
            sessionInitialPageMs = null
            firstImageAnalysisLogged = false
        }
    }

    private fun recordPageLoadTiming(
        pageIndex: Int,
        reason: String,
        cacheHit: Boolean,
        loadStartedAtMs: Long,
        fileReadyAtMs: Long,
        extractMs: Long,
        fileSize: Long,
    ) {
        synchronized(diagnosticLock) {
            pageLoadTimings[pageIndex] = PageLoadDiagnostic(
                reason = reason,
                cacheHit = cacheHit,
                loadStartedAtMs = loadStartedAtMs,
                fileReadyAtMs = fileReadyAtMs,
                extractMs = extractMs,
                fileSize = fileSize,
            )
            if (reason == LOAD_REASON_INITIAL && sessionInitialPageMs == null) {
                val openStartedAtMs = readerOpenStartedAtMs ?: loadStartedAtMs
                sessionInitialPageMs = (fileReadyAtMs - openStartedAtMs).coerceAtLeast(0L)
            }
        }
    }

    private fun markPrefetchPlanned(pages: List<Int>) {
        val plannedAtMs = elapsedRealtimeMs()
        synchronized(diagnosticLock) {
            pages.forEach { page ->
                prefetchDiagnostics[page] = PrefetchDiagnostic(plannedAtMs = plannedAtMs)
            }
        }
    }

    private fun markPrefetchStarted(pageIndex: Int) {
        val startedAtMs = elapsedRealtimeMs()
        synchronized(diagnosticLock) {
            val current = prefetchDiagnostics[pageIndex] ?: PrefetchDiagnostic(plannedAtMs = startedAtMs)
            prefetchDiagnostics[pageIndex] = current.copy(startedAtMs = startedAtMs)
        }
        ReaderDiagnosticLog.event("prefetch_page_start page=$pageIndex")
    }

    private fun markPrefetchCompleted(pageIndex: Int) {
        val completedAtMs = elapsedRealtimeMs()
        synchronized(diagnosticLock) {
            val current = prefetchDiagnostics[pageIndex] ?: PrefetchDiagnostic(plannedAtMs = completedAtMs)
            prefetchDiagnostics[pageIndex] = current.copy(completedAtMs = completedAtMs)
        }
    }

    private fun cancelActivePrefetch(reason: String) {
        val cancelledAtMs = elapsedRealtimeMs()
        val cancelledPages = synchronized(diagnosticLock) {
            prefetchDiagnostics
                .filter { (_, diagnostic) ->
                    diagnostic.completedAtMs == null && diagnostic.cancelledAtMs == null
                }
                .map { (page, diagnostic) ->
                    prefetchDiagnostics[page] = diagnostic.copy(cancelledAtMs = cancelledAtMs)
                    page
                }
        }
        if (cancelledPages.isNotEmpty()) {
            ReaderDiagnosticLog.event("prefetch_cancelled reason=$reason pages=${cancelledPages.sorted()}")
        }
    }

    private fun emitFirstImageAnalysisIfNeeded(pageIndex: Int, completedAtMs: Long, imageRenderMs: Long?) {
        val analysis = synchronized(diagnosticLock) {
            if (firstImageAnalysisLogged) return
            firstImageAnalysisLogged = true
            val openStartedAtMs = readerOpenStartedAtMs ?: completedAtMs
            val pageLoad = pageLoadTimings[pageIndex]
            formatFirstImageAnalysis(
                FirstImageTiming(
                    page = pageIndex,
                    totalMs = (completedAtMs - openStartedAtMs).coerceAtLeast(0L),
                    remoteOpenMs = remoteOpenDurationMs,
                    sessionInitialPageMs = sessionInitialPageMs,
                    pageExtractMs = pageLoad?.extractMs,
                    imageRenderMs = imageRenderMs,
                    cacheHit = pageLoad?.cacheHit ?: false,
                ),
            )
        }
        ReaderDiagnosticLog.event(analysis)
    }

    private fun emitPageNotReadyAnalysisIfNeeded(pageIndex: Int, completedAtMs: Long, imageRenderMs: Long?) {
        val analysis = synchronized(diagnosticLock) {
            val wait = pageWaits.remove(pageIndex) ?: return
            val prefetch = prefetchDiagnostics[pageIndex]
            val pageLoad = pageLoadTimings[pageIndex]
            formatPageNotReadyAnalysis(
                PageNotReadyTiming(
                    page = pageIndex,
                    waitMs = (completedAtMs - wait.startedAtMs).coerceAtLeast(0L),
                    wasPrefetchPlanned = wait.wasPrefetchPlanned,
                    wasPrefetchCancelled = prefetch?.cancelledAtMs != null && prefetch.completedAtMs == null,
                    prefetchStartedBeforeDemand = wait.prefetchStartedBeforeDemand,
                    extractMs = pageLoad?.extractMs,
                    imageRenderMs = imageRenderMs,
                ),
            )
        }
        ReaderDiagnosticLog.event(analysis)
    }

    private fun pageCacheFile(cacheDir: File, pageIndex: Int): File {
        val safeKey = (pageCacheKey ?: "default").replace(Regex("[^A-Za-z0-9._-]"), "_")
        val pageDir = File(cacheDir, "comicdav-pages/$safeKey")
        pageDir.mkdirs()
        return File(pageDir, "page-$pageIndex.img")
    }

    private data class OpenedReader(
        val session: ComicReaderSession,
        val currentPage: Int,
        val files: Map<Int, File>,
    )

    private data class PageLoadDiagnostic(
        val reason: String,
        val cacheHit: Boolean,
        val loadStartedAtMs: Long,
        val fileReadyAtMs: Long,
        val extractMs: Long,
        val fileSize: Long,
    )

    private data class PrefetchDiagnostic(
        val plannedAtMs: Long,
        val startedAtMs: Long? = null,
        val completedAtMs: Long? = null,
        val cancelledAtMs: Long? = null,
    )

    private data class PageWaitDiagnostic(
        val startedAtMs: Long,
        val source: String,
        val wasPrefetchPlanned: Boolean,
        val prefetchStartedBeforeDemand: Boolean,
    )

    private fun closeSessionAsync(session: ComicReaderSession) {
        cleanupScope.launch {
            sessionMutex.withLock {
                session.close()
            }
        }
    }

    private companion object {
        const val PREFETCH_FORWARD_PAGES = 4
        const val PREFETCH_START_DELAY_MS = 150L
        const val NETWORK_WIFI = 2
        const val LOAD_REASON_INITIAL = "initial"
        const val LOAD_REASON_SELECT = "select"
        const val LOAD_REASON_PREFETCH = "prefetch"
    }
}
