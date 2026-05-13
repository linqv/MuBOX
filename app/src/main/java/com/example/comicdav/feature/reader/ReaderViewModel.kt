package com.example.comicdav.feature.reader

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.comicdav.nativebridge.ComicEngine
import com.example.comicdav.nativebridge.ComicReaderSession
import java.io.File
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
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
                    val files = loadPages(openedSession, listOf(startPage), cacheDir = cacheDir)
                    OpenedReader(openedSession, startPage, files)
                }
            }.fold(
                onSuccess = { opened ->
                    if (openGeneration != generation) {
                        opened.session.close()
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
        this.cacheDir = cacheDir
        this.comicKey = comicKey
        this.pageCacheKey = comicKey
        val openGeneration = generation
        uiState = ReaderUiState(isLoading = true)
        viewModelScope.launch {
            runCatching {
                withContext(ioDispatcher) {
                    val startPage = initialPage.coerceIn(0, (openedSession.pageCount - 1).coerceAtLeast(0))
                    val files = loadPages(openedSession, listOf(startPage), cacheDir = cacheDir)
                    OpenedReader(openedSession, startPage, files)
                }
            }.fold(
                onSuccess = { opened ->
                    if (openGeneration != generation) {
                        opened.session.close()
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
                    openedSession.close()
                    uiState = ReaderUiState(error = error.message ?: "Failed to open comic")
                },
            )
        }
    }

    fun selectPage(pageIndex: Int) {
        val activeSession = session ?: return
        val activeCacheDir = cacheDir ?: return
        if (pageIndex !in 0 until activeSession.pageCount) return

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
                    loadPages(activeSession, listOf(pageIndex), activeCacheDir)
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
        generation++
        session?.close()
        session = null
        comicKey = null
        pageCacheKey = null
    }

    private fun prefetchNeighbors(pageIndex: Int) {
        val activeSession = session ?: return
        val activeCacheDir = cacheDir ?: return
        val missingNeighbors = listOf(pageIndex + 1, pageIndex - 1)
            .filter { it in 0 until activeSession.pageCount }
            .filterNot { uiState.pageFiles.containsKey(it) }
        if (missingNeighbors.isEmpty()) return

        val prefetchGeneration = generation
        viewModelScope.launch {
            runCatching {
                withContext(ioDispatcher) {
                    loadPages(activeSession, missingNeighbors, activeCacheDir)
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

    private fun loadPages(
        session: ComicReaderSession,
        pageIndexes: List<Int>,
        cacheDir: File,
    ): Map<Int, File> {
        return pageIndexes
            .distinct()
            .filter { it in 0 until session.pageCount }
            .associateWith { index ->
                session.loadPageToFile(index, pageCacheFile(cacheDir, index))
            }
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
}
