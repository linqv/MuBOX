package com.example.comicdav.feature.library

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.comicdav.data.library.LibraryCatalog
import com.example.comicdav.data.library.LibraryItemWithSources
import kotlinx.coroutines.launch

data class LibraryUiState(
    val items: List<LibraryItemWithSources> = emptyList(),
    val isLoading: Boolean = true,
    val error: String? = null,
    val message: String? = null,
)

class LibraryViewModel(
    private val catalog: LibraryCatalog,
) : ViewModel() {
    var uiState by mutableStateOf(LibraryUiState())
        private set

    init {
        viewModelScope.launch {
            catalog.observeLibrary().collect { items ->
                uiState = uiState.copy(
                    items = items,
                    isLoading = false,
                    error = null,
                )
            }
        }
    }

    fun addLocalComic(
        uri: String,
        fileName: String,
        size: Long? = null,
        lastModified: Long? = null,
    ) {
        viewModelScope.launch {
            runCatching {
                catalog.addLocalComic(
                    uri = uri,
                    fileName = fileName,
                    size = size,
                    lastModified = lastModified,
                )
            }.fold(
                onSuccess = {
                    uiState = uiState.copy(message = "$fileName added to library", error = null)
                },
                onFailure = { error ->
                    uiState = uiState.copy(error = error.message ?: "Failed to add local comic")
                },
            )
        }
    }

    fun addWebDavComic(
        accountId: String,
        remotePath: String,
        fileName: String,
        size: Long? = null,
        etag: String? = null,
        lastModified: Long? = null,
        cacheKey: String? = null,
    ) {
        viewModelScope.launch {
            runCatching {
                catalog.addWebDavComic(
                    accountId = accountId,
                    remotePath = remotePath,
                    fileName = fileName,
                    size = size,
                    etag = etag,
                    lastModified = lastModified,
                    cacheKey = cacheKey,
                )
            }.fold(
                onSuccess = {
                    uiState = uiState.copy(message = "$fileName added to library", error = null)
                },
                onFailure = { error ->
                    uiState = uiState.copy(error = error.message ?: "Failed to add WebDAV comic")
                },
            )
        }
    }

    fun markOpened(libraryItemId: Long) {
        viewModelScope.launch {
            runCatching { catalog.markOpened(libraryItemId) }
        }
    }

    fun showMessage(message: String) {
        uiState = uiState.copy(message = message, error = null)
    }

    fun showError(message: String) {
        uiState = uiState.copy(error = message)
    }

    fun clearMessage() {
        uiState = uiState.copy(message = null, error = null)
    }
}
