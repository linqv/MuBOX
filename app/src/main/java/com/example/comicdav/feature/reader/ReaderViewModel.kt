package com.example.comicdav.feature.reader

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.comicdav.nativebridge.ComicEngine
import com.example.comicdav.nativebridge.ComicReaderSession
import com.example.comicdav.nativebridge.PlannedRemoteRange
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.sync.withLock
import kotlin.math.abs

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
    private val openSession: ComicSessionFactory = { path -> ComicEngine().openLocal(path) },
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val savePage: SaveReadingProgress = { _, _ -> },
    private val prunePageCache: (cacheDir: File, protectedFile: File, maxBytes: Long) -> Unit = { cacheDir, protectedFile, maxBytes ->
        ReaderPageCache.prune(cacheDir, protectedFile, maxBytes)
    },
    private val elapsedRealtimeMs: () -> Long = { System.nanoTime() / 1_000_000L },
) : ViewModel() {
    var uiState by mutableStateOf(ReaderUiState())
        private set

    private var session: ComicReaderSession? = null
    private var cacheDir: File? = null
    private var comicKey: String? = null
    private var pageCacheKey: String? = null
    @Volatile
    private var pageCacheMaxBytes: Long = ReaderPageCache.DEFAULT_MAX_BYTES
    @Volatile
    private var generation = 0
    private var remoteOpenJob: Job? = null
    private var viewportJob: Job? = null
    private val prefetchJobs = mutableMapOf<Int, Job>()
    private val plannedRangeLock = Any()
    private val plannedRangeJobs = mutableMapOf<PlannedRangeKey, PlannedRangePrefetch>()
    private val completedPlannedRanges = mutableMapOf<PlannedRangeKey, CompletedPlannedRange>()
    private var plannedRangeSupervisor = SupervisorJob()
    private var plannedRangeScope = CoroutineScope(plannedRangeSupervisor + ioDispatcher)
    private val plannedRangeSemaphore = Semaphore(MAX_PLANNED_RANGE_CONCURRENCY)
    private val lowPriorityPlannedRangeSemaphore = Semaphore(MAX_LOW_PRIORITY_PLANNED_RANGE_CONCURRENCY)
    private val sessionMutex = Mutex()
    private val cleanupScope = CoroutineScope(SupervisorJob() + ioDispatcher)
    private val diagnostics = ReaderDiagnosticsTracker(elapsedRealtimeMs)

    fun updatePageCacheMaxBytes(maxBytes: Long) {
        pageCacheMaxBytes = maxBytes.coerceAtLeast(1L)
    }

    fun openLocal(path: String, cacheDir: File, initialPage: Int = 0, comicKey: String? = null) {
        closeCurrentSession()
        diagnostics.reset()
        this.cacheDir = cacheDir
        this.comicKey = comicKey
        this.pageCacheKey = comicKey ?: "local-${path.hashCode()}"
        val openGeneration = generation
        val activeReaderKey = readerInstanceKey(requireNotNull(this.pageCacheKey), openGeneration)
        ReaderDiagnosticLog.event("open_local_start initialPage=$initialPage generation=$openGeneration key=$activeReaderKey")
        uiState = ReaderUiState(isLoading = true, readerKey = activeReaderKey)
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
                        readerKey = activeReaderKey,
                    )
                    ReaderDiagnosticLog.event(
                        "open_local_success pageCount=${opened.session.pageCount} current=${opened.currentPage} files=${opened.files.keys.sorted()}",
                    )
                    scheduleViewportUpdate(opened.session, opened.currentPage, openGeneration)
                    prefetchNeighbors(opened.currentPage)
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
    ) {
        closeCurrentSession()
        diagnostics.reset()
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
        val activeReaderKey = readerInstanceKey(comicKey, openGeneration)
        uiState = ReaderUiState(isLoading = true, readerKey = activeReaderKey)
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
                        readerKey = activeReaderKey,
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
                        openGeneration = openGeneration,
                    )
                },
                onFailure = { error ->
                    if (openGeneration != generation || error is CancellationException) return@fold
                    ReaderDiagnosticLog.error("open_remote_failed", error)
                    uiState = ReaderUiState(error = error.message ?: "打开远程漫画失败")
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
        val promotedPrefetch = activePrefetchJob(pageIndex)
        val promotedPlannedRange = activePlannedRangeJob(pageIndex)
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
            prefetchNeighbors(pageIndex, reason = "select_page")
            return
        }
        val loadGeneration = generation
        prefetchNeighbors(pageIndex, reason = "select_page")
        val promotedJob = promotedPrefetch ?: promotedPlannedRange
        if (promotedJob != null) {
            val promotionSource = if (promotedPrefetch != null) {
                "prefetch_to_select"
            } else {
                "planned_range_to_select"
            }
            ReaderDiagnosticLog.detail(ReaderLogCategory.PREFETCH) {
                "prefetch_promoted page=$pageIndex source=$promotionSource"
            }
            viewModelScope.launch {
                runCatching {
                    promotedJob.join()
                    uiState.pageFiles[pageIndex]
                        ?: withContext(ioDispatcher) {
                            loadPages(
                                session = activeSession,
                                pageIndexes = listOf(pageIndex),
                                cacheDir = activeCacheDir,
                                expectedGeneration = loadGeneration,
                                reason = LOAD_REASON_SELECT,
                            )
                        }[pageIndex]
                }.fold(
                    onSuccess = { file ->
                        if (loadGeneration != generation || file == null) return@fold
                        uiState = uiState.copy(
                            currentPage = pageIndex,
                            pageFiles = uiState.pageFiles + (pageIndex to file),
                            isLoading = false,
                        )
                        scheduleViewportUpdate(activeSession, pageIndex, loadGeneration)
                        saveProgress(pageIndex)
                        ReaderDiagnosticLog.event("select_page_loaded page=$pageIndex files=[$pageIndex]")
                        prefetchNeighbors(pageIndex)
                    },
                    onFailure = { error ->
                        if (loadGeneration != generation) return@fold
                        ReaderDiagnosticLog.error("select_page_load_failed page=$pageIndex", error)
                        uiState = uiState.copy(
                            isLoading = false,
                            error = error.message ?: "加载页面失败",
                        )
                    },
                )
            }
            return
        }
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
        plannedRangeSupervisor.cancel(CancellationException("reader view model cleared"))
    }

    private fun closeCurrentSession() {
        val activeSession = session
        ReaderDiagnosticLog.event("close_current_session generation=$generation hasSession=${activeSession != null}")
        if (activeSession != null) {
            diagnostics.localSessionSummary()?.let { summary ->
                ReaderDiagnosticLog.summary(ReaderLogCategory.SESSION) {
                    summary.formatLocalSessionSummary()
                }
            }
        }
        remoteOpenJob?.cancel()
        remoteOpenJob = null
        cancelPagePrefetches(
            reason = "stale_generation",
            pages = prefetchJobs.keys.toList(),
            selectedPage = uiState.currentPage,
        )
        cancelPlannedRangePrefetches(reason = "stale_generation", keepPages = emptySet())
        resetPlannedRangeScope()
        viewportJob?.cancel()
        viewportJob = null
        generation++
        activeSession?.let(::closeSessionAsync)
        session = null
        comicKey = null
        pageCacheKey = null
    }

    fun reportPageDemand(pageIndex: Int, source: String) {
        val ready = uiState.pageFiles[pageIndex] != null
        ReaderDiagnosticLog.detail(ReaderLogCategory.UI) {
            "page_demand page=$pageIndex source=$source ready=$ready"
        }
        if (pageIndex !in 0 until uiState.pageCount) return
        if (ready) {
            val activeSession = session
            if (activeSession?.advancePrefetchOnPageDemand == true && source.shouldAdvancePrefetchOnDemand()) {
                prefetchNeighbors(pageIndex, reason = source)
            }
            return
        }
        if (source == "pager_target") {
            scheduleDemandPlannedRangePrefetch(pageIndex, source)
        }

        diagnostics.recordPageDemand(pageIndex, source)
    }

    private fun String.shouldAdvancePrefetchOnDemand(): Boolean =
        this == "pager_target" || this == "continuous_visible"

    fun reportImageLoadStarted(pageIndex: Int) {
        diagnostics.recordImageLoadStarted(pageIndex)
        ReaderDiagnosticLog.detail(ReaderLogCategory.IMAGE) { "image_load_start page=$pageIndex" }
    }

    fun reportImageLoadSucceeded(pageIndex: Int) {
        val completedAtMs = elapsedRealtimeMs()
        val imageRenderMs = diagnostics.imageRenderDuration(pageIndex, completedAtMs)
        ReaderDiagnosticLog.detail(ReaderLogCategory.IMAGE) {
            "image_load_success page=$pageIndex durationMs=${imageRenderMs ?: "unknown"}"
        }
        diagnostics.firstImageAnalysisIfNeeded(pageIndex, completedAtMs, imageRenderMs)
            ?.let(ReaderDiagnosticLog::event)
        diagnostics.pageNotReadyAnalysisIfNeeded(pageIndex, completedAtMs, imageRenderMs)
            ?.let(ReaderDiagnosticLog::event)
    }

    fun reportImageLoadFailed(pageIndex: Int) {
        ReaderDiagnosticLog.detail(ReaderLogCategory.IMAGE) { "image_load_failed page=$pageIndex" }
    }

    private fun prefetchNeighbors(pageIndex: Int, reason: String = "viewport") {
        val activeSession = session ?: return
        val activeCacheDir = cacheDir ?: return
        val forwardPrefetchPages = activeSession.forwardPrefetchPageCount.coerceAtLeast(0)
        val backwardPrefetchPages = activeSession.backwardPrefetchPageCount.coerceAtLeast(0)
        if (forwardPrefetchPages == 0 && backwardPrefetchPages == 0) {
            ReaderDiagnosticLog.detail(ReaderLogCategory.PREFETCH) {
                "prefetch_skipped reason=session_disabled page=$pageIndex"
            }
            return
        }
        val desiredWindow = ReaderPrefetchPlanner.desiredPageWindow(
            pageIndex = pageIndex,
            pageCount = activeSession.pageCount,
            forwardPages = forwardPrefetchPages,
            backwardPages = backwardPrefetchPages,
        )
        reconcilePagePrefetches(
            selectedPage = pageIndex,
            desiredWindow = desiredWindow,
            reason = reason,
        )
        val missingNeighbors = ReaderPrefetchPlanner.neighborPrefetchPages(
            pageIndex = pageIndex,
            pageCount = activeSession.pageCount,
            forwardPages = forwardPrefetchPages,
            backwardPages = backwardPrefetchPages,
        )
            .filterNot { uiState.pageFiles.containsKey(it) }
            .filterNot { prefetchJobs[it]?.isActive == true }
        if (missingNeighbors.isEmpty()) return

        val prefetchGeneration = generation
        diagnostics.markPrefetchPlanned(missingNeighbors)
        ReaderDiagnosticLog.detail(ReaderLogCategory.PREFETCH) {
            "prefetch_start current=$pageIndex pages=$missingNeighbors generation=$prefetchGeneration"
        }
        missingNeighbors.forEachIndexed { order, page ->
            val job = viewModelScope.launch {
                try {
                    delay(PREFETCH_START_DELAY_MS + order * PREFETCH_STAGGER_MS)
                    currentCoroutineContext().ensureActive()
                    diagnostics.markPrefetchStarted(page)
                    ReaderDiagnosticLog.detail(ReaderLogCategory.PREFETCH) { "prefetch_page_start page=$page" }
                    val files = withContext(ioDispatcher) {
                        loadPages(
                            session = activeSession,
                            pageIndexes = listOf(page),
                            cacheDir = activeCacheDir,
                            expectedGeneration = prefetchGeneration,
                            reason = LOAD_REASON_PREFETCH,
                        )
                    }
                    currentCoroutineContext().ensureActive()
                    if (prefetchGeneration == generation) {
                        uiState = uiState.copy(pageFiles = uiState.pageFiles + files)
                        diagnostics.markPrefetchCompleted(page)
                        ReaderDiagnosticLog.detail(ReaderLogCategory.PREFETCH) {
                            "prefetch_loaded page=$page files=${files.keys.sorted()}"
                        }
                    }
                } catch (error: CancellationException) {
                    // Expected lifecycle cancellation is logged by the reconciler.
                } catch (error: Throwable) {
                    val laterPages = missingNeighbors.drop(order + 1)
                    cancelPagePrefetches(
                        reason = "dependency_failed",
                        pages = laterPages,
                        selectedPage = pageIndex,
                    )
                    ReaderDiagnosticLog.error("prefetch_failed page=$page", error)
                } finally {
                    val currentJob = currentCoroutineContext()[Job]
                    if (currentJob != null && prefetchJobs[page] === currentJob) {
                        prefetchJobs.remove(page)
                    }
                }
            }
            prefetchJobs[page] = job
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
                val plannedRanges = withContext(ioDispatcher) {
                    sessionMutex.withLock {
                        if (expectedGeneration != generation) return@withLock emptyList<PlannedRemoteRange>()
                        ReaderDiagnosticLog.detail(ReaderLogCategory.UI) {
                            "update_viewport_start page=$pageIndex generation=$expectedGeneration"
                        }
                        session.updateViewport(pageIndex, NETWORK_WIFI)
                        val ranges = session.plannedRanges(pageIndex, NETWORK_WIFI)
                        ReaderDiagnosticLog.detail(ReaderLogCategory.UI) {
                            "update_viewport_done page=$pageIndex generation=$expectedGeneration " +
                                "plannedRangeCount=${ranges.size} plannedBytes=${ranges.sumOf { it.endInclusive - it.start + 1 }}"
                        }
                        ranges
                    }
                }
                if (expectedGeneration == generation) {
                    schedulePlannedRangePrefetches(session, plannedRanges, expectedGeneration)
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
                var pageCacheFileToPrune: File? = null
                val output = sessionMutex.withLock {
                    currentCoroutineContext().ensureActive()
                    if (expectedGeneration != generation) {
                        throw CancellationException("reader session changed")
                    }
                    val outputFile = ReaderPageCache.pageFile(cacheDir, pageCacheKey, index)
                    if (outputFile.isFile && outputFile.length() > 0L) {
                        outputFile.setLastModified(System.currentTimeMillis())
                        val durationMs = (elapsedRealtimeMs() - loadStartedAtMs).coerceAtLeast(0L)
                        ReaderDiagnosticLog.detail(ReaderLogCategory.PAGE_LOAD) {
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
                        ReaderDiagnosticLog.detail(ReaderLogCategory.PAGE_LOAD) {
                            "load_page_extract_start page=$index reason=$reason"
                        }
                        val loadedFile = session.loadPageToFile(index, outputFile)
                        loadedFile.setLastModified(System.currentTimeMillis())
                        val readyAtMs = elapsedRealtimeMs()
                        val extractMs = (readyAtMs - extractStartedAtMs).coerceAtLeast(0L)
                        val durationMs = (readyAtMs - loadStartedAtMs).coerceAtLeast(0L)
                        ReaderDiagnosticLog.detail(ReaderLogCategory.PAGE_LOAD) {
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
                        pageCacheFileToPrune = loadedFile
                        loadedFile
                    }
                }
                pageCacheFileToPrune?.let { prunePageCache(cacheDir, it, pageCacheMaxBytes) }
                files[index] = output
        }
        return files
    }

    private fun activePrefetchJob(pageIndex: Int): Job? =
        prefetchJobs[pageIndex]?.takeIf { it.isActive }

    private fun activePlannedRangeJob(pageIndex: Int): Job? =
        synchronized(plannedRangeLock) {
            plannedRangeJobs
                .values
                .firstOrNull { planned ->
                    planned.job.isPendingOrActive() && pageIndex in planned.range.pages
                }
                ?.job
        }

    private fun reconcilePagePrefetches(
        selectedPage: Int,
        desiredWindow: Set<Int>,
        reason: String,
    ) {
        val activePages = prefetchJobs
            .filter { (_, job) -> job.isActive }
            .keys
            .toSet()
        val retainedPages = activePages.intersect(desiredWindow)
        val cancelledPages = activePages.subtract(desiredWindow)

        if (retainedPages.isNotEmpty() && reason.startsWith("select_page")) {
            ReaderDiagnosticLog.detail(ReaderLogCategory.PREFETCH) {
                "prefetch_retained reason=select_page page=$selectedPage pages=${retainedPages.sorted()}"
            }
        }
        cancelPagePrefetches(
            reason = "outside_window",
            pages = cancelledPages.toList(),
            selectedPage = selectedPage,
        )
    }

    private fun cancelPagePrefetches(reason: String, pages: List<Int>, selectedPage: Int) {
        val activePages = pages
            .distinct()
            .filter { prefetchJobs[it] != null }
        if (activePages.isEmpty()) return
        diagnostics.markPrefetchCancelled(activePages)
        activePages.forEach { page ->
            prefetchJobs.remove(page)?.cancel(CancellationException("prefetch $reason"))
        }
        ReaderDiagnosticLog.detail(ReaderLogCategory.PREFETCH) {
            "prefetch_cancelled reason=$reason page=$selectedPage pages=${activePages.sorted()}"
        }
    }

    private fun scheduleDemandPlannedRangePrefetch(pageIndex: Int, source: String) {
        val activeSession = session ?: return
        val expectedGeneration = generation
        plannedRangeScope.launch {
            runCatching {
                val ranges = sessionMutex.withLock {
                    if (expectedGeneration != generation) return@withLock emptyList<PlannedRemoteRange>()
                    activeSession.plannedRanges(pageIndex, NETWORK_WIFI)
                }
                if (expectedGeneration == generation) {
                    ReaderDiagnosticLog.detail(ReaderLogCategory.PREFETCH) {
                        "demand_planned_range_plan page=$pageIndex source=$source " +
                            "count=${ranges.size} bytes=${ranges.sumOf { it.endInclusive - it.start + 1 }}"
                    }
                    schedulePlannedRangePrefetches(activeSession, ranges, expectedGeneration)
                }
            }.onFailure { error ->
                if (expectedGeneration == generation && error !is CancellationException) {
                    ReaderDiagnosticLog.error("demand_planned_range_failed page=$pageIndex source=$source", error)
                }
            }
        }
    }

    private fun schedulePlannedRangePrefetches(
        session: ComicReaderSession,
        ranges: List<PlannedRemoteRange>,
        expectedGeneration: Int,
    ) {
        val mergedRanges = mergeSameStartPlannedRanges(ranges)
        // Continuous vertical scrolling advances the viewport through nearby pages while
        // previous planned ranges are still useful. Keep active nearby work instead of
        // cancelling it just because the exact plan pages changed.
        val retainedPages = plannedRangeProtectionPages(mergedRanges)
        cancelPlannedRangePrefetches(reason = "stale_plan", keepPages = retainedPages)
        if (mergedRanges.isEmpty()) return

        val plannedBytes = mergedRanges.sumOf { it.endInclusive - it.start + 1 }
        ReaderDiagnosticLog.detail(ReaderLogCategory.PREFETCH) {
            "planned_range_prefetch_plan count=${mergedRanges.size} bytes=$plannedBytes generation=$expectedGeneration"
        }
        mergedRanges.sortedBy { it.priority }.forEach { range ->
            val key = range.key()
            val protectedRanges = protectedPlannedByteRanges(mergedRanges, excludedKey = key)
            val job = plannedRangeScope.launch(start = CoroutineStart.LAZY) {
                try {
                    if (expectedGeneration != generation) return@launch
                    ReaderDiagnosticLog.detail(ReaderLogCategory.PREFETCH) {
                        "planned_range_prefetch_start start=${range.start} end=${range.endInclusive} " +
                            "pages=${range.pages} priority=${range.priority}"
                    }
                    val stored = prefetchPlannedRangeWithLimits(session, range, protectedRanges)
                    if (expectedGeneration == generation) {
                        markPlannedRangeCompleted(range)
                        ReaderDiagnosticLog.detail(ReaderLogCategory.PREFETCH) {
                            "planned_range_prefetch_done start=${range.start} end=${range.endInclusive} " +
                                "pages=${range.pages} priority=${range.priority} stored=$stored"
                        }
                    }
                } catch (error: CancellationException) {
                    // Expected stale-plan cancellation is logged by the reconciler.
                } catch (error: Throwable) {
                    if (expectedGeneration == generation) {
                        ReaderDiagnosticLog.error(
                            "planned_range_prefetch_failed start=${range.start} end=${range.endInclusive}",
                            error,
                        )
                    }
                } finally {
                    val currentJob = currentCoroutineContext()[Job]
                    if (currentJob != null) {
                        synchronized(plannedRangeLock) {
                            if (plannedRangeJobs[key]?.job === currentJob) {
                                plannedRangeJobs.remove(key)
                            }
                        }
                    }
                }
            }
            var retained: PlannedRangePrefetch? = null
            val shouldStart = synchronized(plannedRangeLock) {
                when {
                    plannedRangeJobs[key]?.job?.isPendingOrActive() == true -> false
                    completedPlannedRanges[key] != null -> false
                    else -> {
                        retained = activePlannedRangeForPagesLocked(range.pages)
                        if (retained != null) {
                            false
                        } else {
                            plannedRangeJobs[key] = PlannedRangePrefetch(range = range, job = job)
                            true
                        }
                    }
                }
            }
            if (!shouldStart) {
                job.cancel()
                retained?.let { retainedRange ->
                    ReaderDiagnosticLog.detail(ReaderLogCategory.PREFETCH) {
                        "planned_range_prefetch_retained start=${retainedRange.range.start} " +
                            "end=${retainedRange.range.endInclusive} pages=${retainedRange.range.pages} " +
                            "requestedStart=${range.start} requestedEnd=${range.endInclusive}"
                    }
                }
                return@forEach
            }
            job.start()
        }
    }

    private fun mergeSameStartPlannedRanges(ranges: List<PlannedRemoteRange>): List<PlannedRemoteRange> =
        ranges
            .groupBy { it.start }
            .map { (start, group) ->
                PlannedRemoteRange(
                    start = start,
                    endInclusive = group.maxOf { it.endInclusive },
                    pages = group.flatMap { it.pages }.distinct().sorted(),
                    priority = group.minOf { it.priority },
                )
            }

    private suspend fun prefetchPlannedRangeWithLimits(
        session: ComicReaderSession,
        range: PlannedRemoteRange,
        protectedRanges: List<LongRange>,
    ): Boolean =
        plannedRangeSemaphore.withPermit {
            if (range.priority > HIGH_PRIORITY_PLANNED_RANGE_MAX) {
                lowPriorityPlannedRangeSemaphore.withPermit {
                    session.prefetchRange(range.start, range.endInclusive, range.priority, protectedRanges)
                }
            } else {
                session.prefetchRange(range.start, range.endInclusive, range.priority, protectedRanges)
            }
        }

    private fun protectedPlannedByteRanges(
        ranges: List<PlannedRemoteRange>,
        excludedKey: PlannedRangeKey,
    ): List<LongRange> {
        val protectionPages = plannedRangeProtectionPages(ranges)
        val currentPages = ranges.flatMap { it.pages }.toSet()
        val candidates = mutableListOf<PlannedRangeProtectionCandidate>()
        ranges.forEach { range ->
            candidates += PlannedRangeProtectionCandidate(
                key = range.key(),
                sourceRank = PLANNED_RANGE_PROTECTION_SOURCE_CURRENT,
                pages = range.pages.toSet(),
                priority = range.priority,
            )
        }
        synchronized(plannedRangeLock) {
            plannedRangeJobs
                .values
                .filter {
                    it.job.isPendingOrActive() &&
                        it.range.pages.any { page -> page in protectionPages }
                }
                .forEach { planned ->
                    candidates += PlannedRangeProtectionCandidate(
                        key = planned.range.key(),
                        sourceRank = PLANNED_RANGE_PROTECTION_SOURCE_ACTIVE,
                        pages = planned.range.pages.toSet(),
                        priority = planned.range.priority,
                    )
                }
            completedPlannedRanges
                .filter { (_, completed) ->
                    completed.pages.any { page -> page in protectionPages }
                }
                .forEach { (key, completed) ->
                    candidates += PlannedRangeProtectionCandidate(
                        key = key,
                        sourceRank = PLANNED_RANGE_PROTECTION_SOURCE_COMPLETED,
                        pages = completed.pages,
                        priority = completed.priority,
                    )
                }
        }

        val selected = mutableListOf<LongRange>()
        val seen = mutableSetOf<PlannedRangeKey>()
        var selectedBytes = 0L
        val sortedCandidates = candidates.sortedWith(
            compareBy<PlannedRangeProtectionCandidate> { it.sourceRank }
                .thenBy { pageDistance(it.pages, currentPages) }
                .thenBy { it.priority }
                .thenBy { it.byteLength },
        )
        for (candidate in sortedCandidates) {
            if (candidate.key == excludedKey) continue
            if (!seen.add(candidate.key)) continue
            val byteLength = candidate.byteLength
            if (selectedBytes + byteLength > MAX_PLANNED_RANGE_PROTECTED_BYTES) continue
            selected += candidate.key.start..candidate.key.endInclusive
            selectedBytes += byteLength
        }
        return selected
    }

    private fun plannedRangeProtectionPages(ranges: List<PlannedRemoteRange>): Set<Int> {
        val pages = ranges.flatMap { it.pages }
        if (pages.isEmpty()) return emptySet()
        val firstPage = (pages.min() - ReaderPrefetchPlanner.FORWARD_PAGES).coerceAtLeast(0)
        val lastPage = pages.max() + ReaderPrefetchPlanner.FORWARD_PAGES
        return (firstPage..lastPage).toSet()
    }

    private fun pageDistance(candidatePages: Set<Int>, currentPages: Set<Int>): Int {
        if (candidatePages.isEmpty() || currentPages.isEmpty()) return Int.MAX_VALUE
        return candidatePages.minOf { candidate ->
            currentPages.minOf { current -> abs(candidate - current) }
        }
    }

    private fun markPlannedRangeCompleted(range: PlannedRemoteRange) {
        synchronized(plannedRangeLock) {
            completedPlannedRanges[range.key()] = CompletedPlannedRange(
                priority = range.priority,
                pages = range.pages.toSet(),
            )
        }
    }

    private fun activePlannedRangeForPagesLocked(pages: List<Int>): PlannedRangePrefetch? =
        plannedRangeJobs
            .values
            .firstOrNull { planned ->
                planned.job.isPendingOrActive() && planned.range.pages.any { page -> page in pages }
            }

    private fun Job.isPendingOrActive(): Boolean = !isCompleted && !isCancelled

    private fun cancelPlannedRangePrefetches(reason: String, keepPages: Set<Int>) {
        val cancelled = synchronized(plannedRangeLock) {
            plannedRangeJobs
                .filter { (_, planned) ->
                    planned.range.pages.none { page -> page in keepPages }
                }
                .map { (key, _) -> key }
                .toList()
        }
        if (cancelled.isEmpty()) return
        cancelled.forEach { key ->
            synchronized(plannedRangeLock) {
                plannedRangeJobs.remove(key)
            }?.job?.cancel(CancellationException("planned range $reason"))
        }
        ReaderDiagnosticLog.detail(ReaderLogCategory.PREFETCH) {
            "planned_range_prefetch_cancelled reason=$reason count=${cancelled.size}"
        }
    }

    private fun resetPlannedRangeScope() {
        plannedRangeSupervisor.cancel(CancellationException("reader session changed"))
        plannedRangeSupervisor = SupervisorJob()
        plannedRangeScope = CoroutineScope(plannedRangeSupervisor + ioDispatcher)
        synchronized(plannedRangeLock) {
            plannedRangeJobs.clear()
            completedPlannedRanges.clear()
        }
    }

    private data class OpenedReader(
        val session: ComicReaderSession,
        val currentPage: Int,
        val files: Map<Int, File>,
    )

    private data class PlannedRangeKey(
        val start: Long,
        val endInclusive: Long,
    )

    private data class PlannedRangePrefetch(
        val range: PlannedRemoteRange,
        val job: Job,
    )

    private data class CompletedPlannedRange(
        val priority: Int,
        val pages: Set<Int>,
    )

    private data class PlannedRangeProtectionCandidate(
        val key: PlannedRangeKey,
        val sourceRank: Int,
        val pages: Set<Int>,
        val priority: Int,
    ) {
        val byteLength: Long
            get() = key.endInclusive - key.start + 1
    }

    private fun PlannedRemoteRange.key(): PlannedRangeKey =
        PlannedRangeKey(start = start, endInclusive = endInclusive)

    private fun readerInstanceKey(baseKey: String, openGeneration: Int): String =
        "$baseKey#$openGeneration"

    private fun LocalSessionPerformanceSummary.formatLocalSessionSummary(): String =
        "local_session_summary pagesLoaded=$pagesLoaded " +
            "cacheHits=$cacheHits cacheMisses=$cacheMisses " +
            "totalOutputBytes=$totalOutputBytes largestOutputBytes=$largestOutputBytes " +
            "slowestPage=$slowestPage slowestPageMs=$slowestPageMs slowestPageReason=$slowestPageReason"

    private fun closeSessionAsync(session: ComicReaderSession) {
        cleanupScope.launch {
            sessionMutex.withLock {
                session.close()
            }
        }
    }

    private companion object {
        const val PREFETCH_START_DELAY_MS = 150L
        const val PREFETCH_STAGGER_MS = 1L
        const val NETWORK_WIFI = 2
        const val HIGH_PRIORITY_PLANNED_RANGE_MAX = 2
        const val MAX_PLANNED_RANGE_CONCURRENCY = 2
        const val MAX_LOW_PRIORITY_PLANNED_RANGE_CONCURRENCY = 1
        const val MAX_PLANNED_RANGE_PROTECTED_BYTES = 32L * 1024L * 1024L
        const val PLANNED_RANGE_PROTECTION_SOURCE_CURRENT = 0
        const val PLANNED_RANGE_PROTECTION_SOURCE_ACTIVE = 1
        const val PLANNED_RANGE_PROTECTION_SOURCE_COMPLETED = 2
        const val LOAD_REASON_INITIAL = "initial"
        const val LOAD_REASON_SELECT = "select"
        const val LOAD_REASON_PREFETCH = "prefetch"
    }
}
