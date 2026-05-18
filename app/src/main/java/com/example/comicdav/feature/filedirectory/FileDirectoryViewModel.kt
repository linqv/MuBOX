package com.example.comicdav.feature.filedirectory

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.comicdav.data.filedirectory.FileDirectoryCatalog
import com.example.comicdav.data.filedirectory.FileDirectorySourceEntity
import kotlinx.coroutines.launch

data class FileDirectoryBrowserItem(
    val name: String,
    val uri: String,
    val isDirectory: Boolean,
    val size: Long? = null,
    val lastModified: Long? = null,
)

interface LocalDirectoryReader {
    fun rootDocumentUri(treeUri: String): String
    suspend fun listChildren(documentUri: String): List<FileDirectoryBrowserItem>
}

data class FileDirectoryUiState(
    val sources: List<FileDirectorySourceEntity> = emptyList(),
    val entries: List<FileDirectoryBrowserItem> = emptyList(),
    val currentTitle: String? = null,
    val isLoading: Boolean = true,
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
                    baseUrl = baseUrl,
                    username = username,
                    password = password,
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

    fun openLocalSource(source: FileDirectorySourceEntity) {
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
        navigationStack = emptyList()
        uiState = uiState.copy(entries = emptyList(), currentTitle = null, error = null)
    }

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
                    baseUrl = baseUrl,
                    username = username,
                    password = password,
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
        uiState = uiState.copy(isLoading = true, currentTitle = frame.title, error = null)
        viewModelScope.launch {
            runCatching {
                localDirectoryReader.listChildren(frame.documentUri)
            }.fold(
                onSuccess = { entries ->
                    uiState = uiState.copy(
                        entries = entries.sortedWith(compareBy<FileDirectoryBrowserItem> { !it.isDirectory }.thenBy { it.name.lowercase() }),
                        currentTitle = frame.title,
                        isLoading = false,
                    )
                },
                onFailure = { error ->
                    uiState = uiState.copy(
                        isLoading = false,
                        error = error.message ?: "读取目录失败",
                    )
                },
            )
        }
    }

    private data class DirectoryFrame(
        val title: String,
        val documentUri: String,
    )
}
