package com.example.comicdav.feature.filedirectory

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.comicdav.core.model.media.MediaEntry
import com.example.comicdav.core.model.media.fileDirectoryBrowserVideoThumbnailVersion
import com.example.comicdav.core.ports.FileDirectoryCatalog
import com.example.comicdav.core.model.source.FileDirectorySource
import com.example.comicdav.ui.directorylisting.DirectorySortDirection
import com.example.comicdav.ui.directorylisting.DirectorySortField
import com.example.comicdav.ui.directorylisting.DirectoryListingViewMode
import com.example.comicdav.ui.directorylisting.DirectoryVideoThumbnail
import com.example.comicdav.ui.directorylisting.filterAndSortDirectoryEntries
import com.example.comicdav.ui.directorylisting.opposite
import com.example.comicdav.ui.directorylisting.putBoundedDirectoryVideoThumbnail
import com.example.comicdav.core.model.media.MediaKind
import com.example.comicdav.core.model.media.isBrowsableInSources
import com.example.comicdav.core.model.media.mediaKindFor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

typealias FileDirectoryBrowserItem = MediaEntry

interface LocalDirectoryReader {
    fun rootDocumentUri(treeUri: String): String
    suspend fun listChildren(documentUri: String): List<FileDirectoryBrowserItem>
}

internal fun filterBrowsableLocalDirectoryItems(items: List<FileDirectoryBrowserItem>): List<FileDirectoryBrowserItem> =
    items.filter { it.mediaKind.isBrowsableInSources }

data class FileDirectoryUiState(
    val sources: List<FileDirectorySource> = emptyList(),
    val entries: List<FileDirectoryBrowserItem> = emptyList(),
    val searchQuery: String = "",
    val sortField: DirectorySortField = DirectorySortField.NAME,
    val sortDirection: DirectorySortDirection = DirectorySortDirection.ASCENDING,
    val viewMode: DirectoryListingViewMode = DirectoryListingViewMode.LIST,
    val videoThumbnails: Map<String, DirectoryVideoThumbnail> = emptyMap(),
    val thumbnailRequestRevision: Long = 0L,
    val currentTitle: String? = null,
    val breadcrumbLabels: List<String> = emptyList(),
    val isLoading: Boolean = true,
    val isRefreshing: Boolean = false,
    val error: String? = null,
    val message: String? = null,
)

class FileDirectoryViewModel(
    private val catalog: FileDirectoryCatalog,
    private val localDirectoryReader: LocalDirectoryReader,
) : ViewModel() {
    var uiState by mutableStateOf(FileDirectoryUiState())
        private set

    private var navigationStack: List<DirectoryFrame> = emptyList()
    private var currentDirectoryEntries: List<FileDirectoryBrowserItem> = emptyList()
    private var directoryLoadJob: Job? = null
    private var directoryLoadGeneration: Long = 0L

    init {
        viewModelScope.launch {
            catalog.observeSources().collect { sources ->
                uiState = uiState.copy(
                    sources = sources,
                    isLoading = false,
                    error = null,
                )
            }
        }
    }

    fun addLocalDirectory(displayName: String, treeUri: String) {
        viewModelScope.launch {
            runCatching {
                catalog.addLocalDirectory(displayName, treeUri)
            }.fold(
                onSuccess = {
                    uiState = uiState.copy(message = "已保存来源：$displayName", error = null)
                },
                onFailure = { error ->
                    uiState = uiState.copy(error = error.message ?: "添加本地来源失败")
                },
            )
        }
    }

    @Suppress("UNUSED_PARAMETER")
    fun addWebDavDirectory(
        displayName: String,
        accountId: String,
        path: String,
        baseUrl: String = "",
        username: String = "",
        password: String = "",
    ) {
        viewModelScope.launch {
            runCatching {
                catalog.addWebDavDirectory(
                    displayName = displayName,
                    accountId = accountId,
                    path = path,
                )
            }.fold(
                onSuccess = {
                    uiState = uiState.copy(message = "已保存来源：$displayName", error = null)
                },
                onFailure = { error ->
                    uiState = uiState.copy(error = error.message ?: "添加 WebDAV 来源失败")
                },
            )
        }
    }

    fun openLocalSource(source: FileDirectorySource) {
        val treeUri = source.localTreeUri ?: return
        val rootDocumentUri = localDirectoryReader.rootDocumentUri(treeUri)
        navigationStack = listOf(DirectoryFrame(source.displayName, rootDocumentUri))
        loadCurrentFrame()
    }

    fun openLocalDirectory(item: FileDirectoryBrowserItem) {
        if (!item.isDirectory) return
        navigationStack = navigationStack + DirectoryFrame(item.name, item.uri)
        loadCurrentFrame()
    }

    fun goUp() {
        if (navigationStack.size <= 1) {
            closeLocalBrowser()
        } else {
            navigationStack = navigationStack.dropLast(1)
            loadCurrentFrame()
        }
    }

    fun handleBack(): Boolean {
        if (navigationStack.isEmpty()) return false
        goUp()
        return true
    }

    fun closeLocalBrowser() {
        directoryLoadGeneration += 1
        directoryLoadJob?.cancel()
        directoryLoadJob = null
        navigationStack = emptyList()
        currentDirectoryEntries = emptyList()
        uiState = uiState.copy(
            entries = emptyList(),
            searchQuery = "",
            videoThumbnails = emptyMap(),
            currentTitle = null,
            breadcrumbLabels = emptyList(),
            isLoading = false,
            isRefreshing = false,
            error = null,
        )
    }

    fun refreshCurrentDirectory() {
        val frame = navigationStack.lastOrNull() ?: return
        if (uiState.isLoading || uiState.isRefreshing) return
        loadFrame(frame = frame, isRefresh = true)
    }

    fun updateSearchQuery(query: String) {
        uiState = uiState.copy(
            searchQuery = query,
            entries = visibleEntries(query = query),
        )
    }

    fun updateSortField(sortField: DirectorySortField) {
        uiState = uiState.copy(
            sortField = sortField,
            entries = visibleEntries(sortField = sortField),
        )
    }

    fun toggleSortDirection() {
        val direction = uiState.sortDirection.opposite()
        uiState = uiState.copy(
            sortDirection = direction,
            entries = visibleEntries(sortDirection = direction),
        )
    }

    fun toggleViewMode() {
        uiState = uiState.copy(
            viewMode = when (uiState.viewMode) {
                DirectoryListingViewMode.LIST -> DirectoryListingViewMode.GRID
                DirectoryListingViewMode.GRID -> DirectoryListingViewMode.LIST
            },
        )
    }

    fun onVideoThumbnailExtracted(
        uri: String,
        version: String,
        thumbnailPath: String,
    ) {
        val previousRevision = uiState.videoThumbnails[uri]?.artworkRevision ?: 0L
        uiState = uiState.copy(
            videoThumbnails = putBoundedDirectoryVideoThumbnail(
                thumbnails = uiState.videoThumbnails,
                key = uri,
                thumbnail = DirectoryVideoThumbnail(
                    version = version,
                    path = thumbnailPath,
                    artworkRevision = previousRevision + 1L,
                ),
            ),
        )
    }

    fun playbackDirectoryEntries(): List<FileDirectoryBrowserItem> =
        visibleEntries(query = "")

    fun deleteSource(id: Long) {
        viewModelScope.launch {
            runCatching {
                catalog.deleteSource(id)
            }.fold(
                onSuccess = {
                    uiState = uiState.copy(message = "已删除来源", error = null)
                },
                onFailure = { error ->
                    uiState = uiState.copy(error = error.message ?: "删除来源失败")
                },
            )
        }
    }

    @Suppress("UNUSED_PARAMETER")
    fun updateWebDavDirectory(
        id: Long,
        displayName: String,
        accountId: String,
        path: String,
        baseUrl: String,
        username: String,
        password: String,
    ) {
        viewModelScope.launch {
            runCatching {
                catalog.updateWebDavDirectory(
                    id = id,
                    displayName = displayName,
                    accountId = accountId,
                    path = path,
                )
            }.fold(
                onSuccess = {
                    uiState = uiState.copy(message = "已更新来源：$displayName", error = null)
                },
                onFailure = { error ->
                    uiState = uiState.copy(error = error.message ?: "更新 WebDAV 来源失败")
                },
            )
        }
    }

    fun clearMessage() {
        uiState = uiState.copy(message = null, error = null)
    }

    fun showMessage(message: String) {
        uiState = uiState.copy(message = message, error = null)
    }

    fun showError(message: String) {
        uiState = uiState.copy(error = message)
    }

    private fun loadCurrentFrame() {
        val frame = navigationStack.lastOrNull() ?: return
        loadFrame(frame = frame, isRefresh = false)
    }

    private fun loadFrame(frame: DirectoryFrame, isRefresh: Boolean) {
        directoryLoadJob?.cancel()
        val loadGeneration = ++directoryLoadGeneration
        val breadcrumbLabels = navigationStack.map(DirectoryFrame::title)
        uiState = if (isRefresh) {
            uiState.copy(isRefreshing = true, error = null)
        } else {
            uiState.copy(
                isLoading = true,
                isRefreshing = false,
                searchQuery = "",
                currentTitle = frame.title,
                breadcrumbLabels = breadcrumbLabels,
                error = null,
            )
        }
        directoryLoadJob = viewModelScope.launch {
            try {
                val entries = localDirectoryReader.listChildren(frame.documentUri)
                if (loadGeneration != directoryLoadGeneration) return@launch
                if (navigationStack.lastOrNull() != frame) return@launch
                currentDirectoryEntries = filterBrowsableLocalDirectoryItems(entries)
                uiState = uiState.copy(
                    entries = visibleEntries(),
                    currentTitle = frame.title,
                    breadcrumbLabels = breadcrumbLabels,
                    isLoading = false,
                    isRefreshing = false,
                    thumbnailRequestRevision = uiState.thumbnailRequestRevision + 1,
                )
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                if (loadGeneration != directoryLoadGeneration) return@launch
                if (navigationStack.lastOrNull() != frame) return@launch
                uiState = uiState.copy(
                    isLoading = false,
                    isRefreshing = false,
                    error = error.message ?: "读取目录失败",
                )
            }
        }
    }

    private fun visibleEntries(
        query: String = uiState.searchQuery,
        sortField: DirectorySortField = uiState.sortField,
        sortDirection: DirectorySortDirection = uiState.sortDirection,
    ): List<FileDirectoryBrowserItem> = filterAndSortDirectoryEntries(
        entries = currentDirectoryEntries,
        query = query,
        sortField = sortField,
        sortDirection = sortDirection,
        nameOf = FileDirectoryBrowserItem::name,
        sizeOf = FileDirectoryBrowserItem::size,
    )

    private data class DirectoryFrame(
        val title: String,
        val documentUri: String,
    )
}
