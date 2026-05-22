package com.example.comicdav.feature.videolibrary

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.comicdav.data.videolibrary.VideoLibraryCatalog
import com.example.comicdav.data.videolibrary.VideoLibraryItemWithSources
import kotlinx.coroutines.launch

data class VideoLibraryUiState(
    val items: List<VideoLibraryItemWithSources> = emptyList(),
    val isLoading: Boolean = true,
    val error: String? = null,
    val message: String? = null,
)

class VideoLibraryViewModel(
    private val catalog: VideoLibraryCatalog,
) : ViewModel() {
    var uiState by mutableStateOf(VideoLibraryUiState())
        private set

    init {
        viewModelScope.launch {
            catalog.observeVideoLibrary().collect { items ->
                uiState = uiState.copy(
                    items = items,
                    isLoading = false,
                    error = null,
                )
            }
        }
    }

    fun addLocalVideo(
        uri: String,
        fileName: String,
        size: Long? = null,
        lastModified: Long? = null,
        thumbnailPath: String? = null,
    ) {
        viewModelScope.launch {
            runCatching {
                catalog.addLocalVideo(
                    uri = uri,
                    fileName = fileName,
                    size = size,
                    lastModified = lastModified,
                    thumbnailPath = thumbnailPath,
                )
            }.fold(
                onSuccess = {
                    uiState = uiState.copy(message = "已将 $fileName 加入影视库", error = null)
                },
                onFailure = { error ->
                    uiState = uiState.copy(
                        error = error.message ?: "添加本地视频失败",
                        message = null,
                    )
                },
            )
        }
    }

    fun addWebDavVideo(
        accountId: String,
        remotePath: String,
        fileName: String,
        size: Long? = null,
        etag: String? = null,
        lastModified: Long? = null,
        thumbnailPath: String? = null,
    ) {
        viewModelScope.launch {
            runCatching {
                catalog.addWebDavVideo(
                    accountId = accountId,
                    remotePath = remotePath,
                    fileName = fileName,
                    size = size,
                    etag = etag,
                    lastModified = lastModified,
                    thumbnailPath = thumbnailPath,
                )
            }.fold(
                onSuccess = {
                    uiState = uiState.copy(message = "已将 $fileName 加入影视库", error = null)
                },
                onFailure = { error ->
                    uiState = uiState.copy(
                        error = error.message ?: "添加 WebDAV 视频失败",
                        message = null,
                    )
                },
            )
        }
    }

    fun markOpened(videoLibraryItemId: Long) {
        viewModelScope.launch {
            runCatching { catalog.markOpened(videoLibraryItemId) }
        }
    }

    fun updateThumbnailPath(videoLibraryItemId: Long, thumbnailPath: String?) {
        viewModelScope.launch {
            runCatching {
                catalog.updateThumbnailPath(videoLibraryItemId, thumbnailPath)
            }.onFailure { error ->
                uiState = uiState.copy(
                    error = error.message ?: "更新视频封面失败",
                    message = null,
                )
            }
        }
    }

    fun removeVideo(videoLibraryItemId: Long) {
        viewModelScope.launch {
            runCatching {
                catalog.removeVideo(videoLibraryItemId)
            }.fold(
                onSuccess = {
                    uiState = uiState.copy(message = "已从影视库移除", error = null)
                },
                onFailure = { error ->
                    uiState = uiState.copy(
                        error = error.message ?: "移除视频失败",
                        message = null,
                    )
                },
            )
        }
    }

    fun showMessage(message: String) {
        uiState = uiState.copy(message = message, error = null)
    }

    fun showError(message: String) {
        uiState = uiState.copy(error = message, message = null)
    }

    fun clearMessage() {
        uiState = uiState.copy(message = null, error = null)
    }
}
