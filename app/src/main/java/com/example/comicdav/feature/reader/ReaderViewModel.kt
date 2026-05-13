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
) : ViewModel() {
    var uiState by mutableStateOf(ReaderUiState())
        private set

    private var session: ComicReaderSession? = null
    private var cacheDir: File? = null

    fun openLocal(path: String, cacheDir: File) {
        closeCurrentSession()
        this.cacheDir = cacheDir
        uiState = ReaderUiState(isLoading = true)
        viewModelScope.launch {
            runCatching {
                withContext(ioDispatcher) {
                    val openedSession = openSession(path)
                    val files = loadAround(openedSession, pageIndex = 0, cacheDir = cacheDir)
                    openedSession to files
                }
            }.fold(
                onSuccess = { (openedSession, files) ->
                    session = openedSession
                    uiState = ReaderUiState(
                        pageCount = openedSession.pageCount,
                        currentPage = 0,
                        pageFiles = files,
                    )
                },
                onFailure = { error ->
                    uiState = ReaderUiState(error = error.message ?: "Failed to open comic")
                },
            )
        }
    }

    fun selectPage(pageIndex: Int) {
        val activeSession = session ?: return
        val activeCacheDir = cacheDir ?: return
        if (pageIndex !in 0 until activeSession.pageCount) return

        uiState = uiState.copy(currentPage = pageIndex, isLoading = true, error = null)
        viewModelScope.launch {
            runCatching {
                withContext(ioDispatcher) {
                    loadAround(activeSession, pageIndex, activeCacheDir)
                }
            }.fold(
                onSuccess = { files ->
                    uiState = uiState.copy(
                        currentPage = pageIndex,
                        pageFiles = uiState.pageFiles + files,
                        isLoading = false,
                    )
                },
                onFailure = { error ->
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
        session?.close()
        session = null
    }

    private fun loadAround(
        session: ComicReaderSession,
        pageIndex: Int,
        cacheDir: File,
    ): Map<Int, File> {
        return (pageIndex - 1..pageIndex + 1)
            .filter { it in 0 until session.pageCount }
            .associateWith { index ->
                session.loadPageToFile(index, pageCacheFile(cacheDir, index))
            }
    }

    private fun pageCacheFile(cacheDir: File, pageIndex: Int): File {
        return File(cacheDir, "comicdav-page-$pageIndex.img")
    }
}
