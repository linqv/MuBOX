package com.example.comicdav

import android.content.Context
import android.net.Uri
import com.example.comicdav.core.model.source.FileDirectorySource
import com.example.comicdav.core.model.source.FileDirectorySourceType
import com.example.comicdav.ui.decodeWebDavPathForDisplay
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal data class AppSourceActionCallbacks(
    val setError: (String?) -> Unit,
    val setActionMessage: (String?) -> Unit,
    val setWebDavOpen: (Boolean) -> Unit,
    val setAddingWebDavPath: (Boolean) -> Unit,
    val setEditingWebDavSourceId: (Long?) -> Unit,
    val selectTab: (AppTab) -> Unit,
)

internal class AppSourceActions(
    private val context: Context,
    private val scope: CoroutineScope,
    private val container: AppContainer,
    private val viewModels: AppViewModels,
    private val callbacks: AppSourceActionCallbacks,
) {
    private val webDavViewModel = viewModels.webDav
    private val fileDirectoryViewModel = viewModels.fileDirectory

    fun addLocalDirectory(displayName: String, treeUri: String) {
        fileDirectoryViewModel.addLocalDirectory(displayName = displayName, treeUri = treeUri)
    }

    fun deleteSource(source: FileDirectorySource) {
        fileDirectoryViewModel.deleteSource(source.id)
    }

    fun deleteLocalSourceWithFiles(source: FileDirectorySource) {
        val treeUriText = source.localTreeUri
        if (treeUriText.isNullOrBlank()) {
            deleteSource(source)
            return
        }
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    deleteLocalSourceTree(context, Uri.parse(treeUriText))
                }
            }.fold(
                onSuccess = {
                    deleteSource(source)
                    fileDirectoryViewModel.showMessage("已删除来源和源文件")
                },
                onFailure = { error ->
                    container.diagnostics.error("delete_local_source_files_failed uri=$treeUriText", error)
                    fileDirectoryViewModel.showError(error.message ?: "删除源文件失败")
                },
            )
        }
    }

    fun startAddingWebDavSource() {
        clearMessages()
        callbacks.setEditingWebDavSourceId(null)
        webDavViewModel.startNewConnection()
        callbacks.setWebDavOpen(true)
        callbacks.setAddingWebDavPath(true)
    }

    fun openSource(source: FileDirectorySource) {
        when (source.sourceType) {
            FileDirectorySourceType.LOCAL -> {
                callbacks.setActionMessage(null)
                fileDirectoryViewModel.openLocalSource(source)
            }
            FileDirectorySourceType.WEBDAV -> openWebDavSource(source)
        }
    }

    fun editWebDavSource(source: FileDirectorySource) {
        val baseUrl = source.webDavBaseUrl
            ?.takeIf { it.isNotBlank() }
            ?: source.webDavAccountId?.substringBefore("|").orEmpty()
        webDavViewModel.editSavedConnection(
            displayName = source.displayName,
            baseUrl = baseUrl,
            username = source.webDavUsername,
            password = source.webDavPassword,
            path = source.webDavPath ?: "/",
        )
        callbacks.setEditingWebDavSourceId(source.id)
        callbacks.setAddingWebDavPath(false)
        callbacks.setWebDavOpen(true)
        clearMessages()
    }

    fun saveCurrentWebDavDirectory() {
        val state = webDavViewModel.uiState
        fileDirectoryViewModel.addWebDavDirectory(
            displayName = decodeWebDavPathForDisplay(state.currentPath),
            accountId = currentWebDavAccountId(),
            path = state.currentPath,
            baseUrl = state.baseUrl,
            username = state.username,
            password = state.password,
        )
        callbacks.setAddingWebDavPath(false)
    }

    fun saveConnectedWebDavSource(editingSourceId: Long?) {
        val state = webDavViewModel.uiState
        val username = if (state.anonymousAccess) "" else state.username
        val password = if (state.anonymousAccess) "" else state.password
        val displayName = state.displayName
            .takeIf { it.isNotBlank() }
            ?: state.host.takeIf { it.isNotBlank() }
            ?: state.baseUrl
        val accountId = "${state.baseUrl.trim()}|$username"
        if (editingSourceId != null) {
            fileDirectoryViewModel.updateWebDavDirectory(
                id = editingSourceId,
                displayName = displayName,
                accountId = accountId,
                path = state.currentPath,
                baseUrl = state.baseUrl,
                username = username,
                password = password,
            )
        } else {
            fileDirectoryViewModel.addWebDavDirectory(
                displayName = displayName,
                accountId = accountId,
                path = "/",
                baseUrl = state.baseUrl,
                username = username,
                password = password,
            )
        }
        resetWebDavNavigationState()
    }

    fun closeWebDav() {
        resetWebDavNavigationState()
        clearMessages()
    }

    fun dismissMessage() {
        callbacks.setError(null)
        fileDirectoryViewModel.clearMessage()
    }

    private fun openWebDavSource(source: FileDirectorySource) {
        val expectedAccountId = source.webDavAccountId
        val path = source.webDavPath ?: "/"
        callbacks.setActionMessage(null)
        callbacks.setAddingWebDavPath(false)
        callbacks.setEditingWebDavSourceId(null)

        if (expectedAccountId != null && webDavViewModel.activeAccountId() == expectedAccountId) {
            clearMessages()
            webDavViewModel.openPath(path)
            callbacks.setWebDavOpen(true)
            return
        }

        scope.launch {
            val savedAccount = expectedAccountId?.let { accountId ->
                container.webDavAccountStore.loadAccount(accountId)
            }
            val baseUrl = source.webDavBaseUrl
                ?.takeIf { it.isNotBlank() }
                ?: savedAccount?.baseUrl
            if (baseUrl.isNullOrBlank()) {
                callbacks.setError("请先连接 ${expectedAccountId.orEmpty()}，再打开这个 WebDAV 目录")
                callbacks.setActionMessage(null)
                return@launch
            }
            clearMessages()
            webDavViewModel.connectToSavedSource(
                baseUrl = baseUrl,
                username = source.webDavUsername
                    ?.takeIf { it.isNotBlank() }
                    ?: savedAccount?.username,
                password = source.webDavPassword
                    ?.takeIf { it.isNotBlank() }
                    ?: savedAccount?.password,
                path = path,
            )
            callbacks.setWebDavOpen(true)
        }
    }

    private fun clearMessages() {
        callbacks.setError(null)
        callbacks.setActionMessage(null)
    }

    private fun resetWebDavNavigationState() {
        callbacks.setWebDavOpen(false)
        callbacks.setAddingWebDavPath(false)
        callbacks.setEditingWebDavSourceId(null)
    }

    private fun currentWebDavAccountId(): String =
        webDavViewModel.activeAccountId() ?: webDavViewModel.accountId()
}
