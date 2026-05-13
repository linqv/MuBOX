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
import kotlinx.coroutines.delay
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
    private val sessionMutex = Mutex()
    private val cleanupScope = CoroutineScope(SupervisorJob() + ioDispatcher)

    fun openLocal(path: String, cacheDir: File, initialPage: Int = 0, comicKey: String? = null) {
        closeCurrentSession()
        this.cacheDir = cacheDir
        this.comicKey = comicKey
        this.pageCacheKey = comicKey ?: "local-${path.hashCode()}"
        val openGeneration = generation
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
                    prefetchNeighbors(opened.currentPage)
                },
                onFailure = { error ->
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
                    prefetchNeighbors(opened.currentPage)
                },
                onFailure = { error ->
                    closeSessionAsync(openedSession)
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
        this.cacheDir = cacheDir
        val openGeneration = generation
        uiState = ReaderUiState(isLoading = true)
        remoteOpenJob = viewModelScope.launch {
            runCatching {
                openComic()
            }.fold(
                onSuccess = { result ->
                    if (openGeneration != generation) {
                        closeSessionAsync(result.session)
                        return@fold
                    }
                    remoteOpenJob = null
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
                    uiState = ReaderUiState(error = error.message ?: "Failed to open remote comic")
                },
            )
        }
    }

    fun selectPage(pageIndex: Int) {
        val activeSession = session ?: return
        val activeCacheDir = cacheDir ?: return
        if (pageIndex !in 0 until activeSession.pageCount) return

        prefetchJob?.cancel()
        prefetchJob = null
        val existingFile = uiState.pageFiles[pageIndex]
        uiState = uiState.copy(
            currentPage = pageIndex,
            isLoading = existingFile == null,
            error = null,
        )
        if (existingFile != null) {
            saveProgress(pageIndex)
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
                    saveProgress(pageIndex)
                    prefetchNeighbors(pageIndex)
                },
                onFailure = { error ->
                    if (loadGeneration != generation) return@fold
                    uiState = uiState.copy(
                        isLoading = false,
                        error = error.message ?: "Failed to load page",
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
    }

    private fun closeCurrentSession() {
        remoteOpenJob?.cancel()
        remoteOpenJob = null
        prefetchJob?.cancel()
        prefetchJob = null
        generation++
        session?.let(::closeSessionAsync)
        session = null
        comicKey = null
        pageCacheKey = null
    }

    private fun prefetchNeighbors(pageIndex: Int) {
        val activeSession = session ?: return
        val activeCacheDir = cacheDir ?: return
        val forwardPages = (1..PREFETCH_FORWARD_PAGES).map { pageIndex + it }
        val missingNeighbors = (forwardPages + (pageIndex - 1))
            .filter { it in 0 until activeSession.pageCount }
            .filterNot { uiState.pageFiles.containsKey(it) }
        if (missingNeighbors.isEmpty()) return

        prefetchJob?.cancel()
        val prefetchGeneration = generation
        prefetchJob = viewModelScope.launch {
            delay(PREFETCH_START_DELAY_MS)
            runCatching {
                withContext(ioDispatcher) {
                    loadPages(
                        session = activeSession,
                        pageIndexes = missingNeighbors,
                        cacheDir = activeCacheDir,
                        expectedGeneration = prefetchGeneration,
                    )
                }
            }.onSuccess { files ->
                if (prefetchGeneration == generation) {
                    uiState = uiState.copy(pageFiles = uiState.pageFiles + files)
                }
            }
        }
    }

    private fun saveProgress(pageIndex: Int) {
        comicKey?.let { key ->
            viewModelScope.launch {
                savePage(key, pageIndex)
            }
        }
    }

    private suspend fun loadPages(
        session: ComicReaderSession,
        pageIndexes: List<Int>,
        cacheDir: File,
        expectedGeneration: Int,
    ): Map<Int, File> {
        val files = linkedMapOf<Int, File>()
        pageIndexes
            .distinct()
            .filter { it in 0 until session.pageCount }
            .forEach { index ->
                if (expectedGeneration != generation) {
                    throw CancellationException("reader session changed")
                }
                val output = sessionMutex.withLock {
                    if (expectedGeneration != generation) {
                        throw CancellationException("reader session changed")
                    }
                    val outputFile = pageCacheFile(cacheDir, index)
                    if (outputFile.isFile && outputFile.length() > 0L) {
                        outputFile
                    } else {
                        session.loadPageToFile(index, outputFile)
                    }
                }
                files[index] = output
            }
        return files
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
    }
}
