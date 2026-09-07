package org.mubox.reader

import android.content.Context
import android.net.Uri
import org.mubox.reader.core.model.source.FileDirectorySource
import org.mubox.reader.core.model.source.FileDirectorySourceType
import org.mubox.reader.feature.webdav.buildWebDavBaseUrl
import org.mubox.reader.ui.decodeWebDavPathForDisplay
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
        val expectedAccountId = source.webDavAccountId
        scope.launch {
            val savedAccount = expectedAccountId?.let { accountId ->
                container.webDavAccountStore.loadAccount(accountId)
            }
            val baseUrl = source.webDavBaseUrl
                ?.takeIf { it.isNotBlank() }
                ?: savedAccount?.baseUrl
                ?: expectedAccountId?.substringBefore("|").orEmpty()
            val username = source.webDavUsername
                ?.takeIf { it.isNotBlank() }
                ?: savedAccount?.username
                ?: expectedAccountId?.substringAfter("|").orEmpty()
            val password = source.webDavPassword
                ?.takeIf { it.isNotBlank() }
                ?: savedAccount?.password
                .orEmpty()
            webDavViewModel.editSavedConnection(
                displayName = source.displayName,
                baseUrl = baseUrl,
                username = username,
                password = password,
                path = source.webDavPath ?: "/",
            )
            callbacks.setEditingWebDavSourceId(source.id)
            callbacks.setAddingWebDavPath(false)
            callbacks.setWebDavOpen(true)
            clearMessages()
        }
    }

    fun saveCurrentWebDavDirectory() {
        val state = webDavViewModel.uiState
        fileDirectoryViewModel.addWebDavDirectory(
            displayName = decodeWebDavPathForDisplay(state.currentPath),
            accountId = currentWebDavAccountId(),
            path = state.currentPath,
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
        val normalizedBaseUrl = state.baseUrl.trim().ifBlank {
            buildWebDavBaseUrl(
                useHttps = state.useHttps,
                host = state.host,
                port = state.port,
                rootPath = state.rootPath,
            )
        }
        val accountId = "${normalizedBaseUrl}|$username"
        scope.launch {
            if (normalizedBaseUrl.isNotBlank()) {
                container.webDavAccountStore.saveAccount(
                    baseUrl = normalizedBaseUrl,
                    username = username,
                    password = password,
                )
            }
            if (editingSourceId != null) {
                fileDirectoryViewModel.updateWebDavDirectory(
                    id = editingSourceId,
                    displayName = displayName,
                    accountId = accountId,
                    path = state.currentPath.ifBlank { "/" },
                )
            } else {
                fileDirectoryViewModel.addWebDavDirectory(
                    displayName = displayName,
                    accountId = accountId,
                    path = state.currentPath.ifBlank { "/" },
                )
            }
            resetWebDavNavigationState()
        }
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
                ?: expectedAccountId?.substringBefore("|")?.takeIf { it.isNotBlank() }
            val username = source.webDavUsername
                ?.takeIf { it.isNotBlank() }
                ?: savedAccount?.username
                ?: expectedAccountId?.substringAfter("|").orEmpty()
            val password = source.webDavPassword
                ?.takeIf { it.isNotBlank() }
                ?: savedAccount?.password
                .orEmpty()

            if (baseUrl.isNullOrBlank() || (savedAccount == null && password.isBlank() && username.isNotBlank())) {
                editWebDavSource(source)
                callbacks.setError("请先配置并连接 WebDAV 账户")
                callbacks.setActionMessage(null)
                return@launch
            }
            clearMessages()
            webDavViewModel.connectToSavedSource(
                baseUrl = baseUrl,
                username = username,
                password = password,
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
