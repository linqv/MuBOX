package com.example.comicdav

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.example.comicdav.feature.filedirectory.FileDirectoryUiState
import com.example.comicdav.feature.filedirectory.FileDirectoryViewModel
import com.example.comicdav.feature.webdav.DownloadProgressUi
import com.example.comicdav.feature.webdav.WEB_DAV_STATUS_CONNECTED
import com.example.comicdav.feature.webdav.WebDavAccountScreen
import com.example.comicdav.feature.webdav.WebDavBrowserScreen
import com.example.comicdav.feature.webdav.WebDavItemClickAction
import com.example.comicdav.feature.webdav.WebDavUiState
import com.example.comicdav.feature.webdav.WebDavViewModel
import com.example.comicdav.feature.webdav.webDavItemClickAction

internal data class AppSourcesRouteState(
    val webDavUiState: WebDavUiState,
    val fileDirectoryUiState: FileDirectoryUiState,
    val isWebDavOpen: Boolean,
    val isAddingWebDavPath: Boolean,
    val editingWebDavSourceId: Long?,
    val localOpenError: String?,
    val actionMessage: String?,
    val downloadProgress: DownloadProgressUi?,
    val selection: AppSelection,
)

@Composable
internal fun AppSourcesRoute(
    state: AppSourcesRouteState,
    webDavViewModel: WebDavViewModel,
    fileDirectoryViewModel: FileDirectoryViewModel,
    sourceActions: AppSourceActions,
    comicActions: AppComicActions,
    videoActions: AppVideoActions,
    downloadActions: AppDownloadActions,
    onChooseLocalDirectory: () -> Unit,
    onSelectionChange: (AppSelection) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (!state.isWebDavOpen) {
        LocalSourcesRoute(
            state = state,
            fileDirectoryViewModel = fileDirectoryViewModel,
            sourceActions = sourceActions,
            comicActions = comicActions,
            videoActions = videoActions,
            onChooseLocalDirectory = onChooseLocalDirectory,
            onSelectionChange = onSelectionChange,
            modifier = modifier,
        )
        return
    }

    when {
        state.webDavUiState.status == WEB_DAV_STATUS_CONNECTED -> {
            WebDavBrowserScreen(
                uiState = state.webDavUiState,
                onItemClick = { item ->
                    when (webDavItemClickAction(item)) {
                        WebDavItemClickAction.OpenDirectory -> webDavViewModel.openDirectory(item)
                        WebDavItemClickAction.OpenComic -> comicActions.openRemoteComic(
                            accountId = webDavViewModel.activeAccountId() ?: webDavViewModel.accountId(),
                            remotePath = item.path,
                            size = item.size,
                            etag = item.etag,
                            lastModified = item.lastModified,
                        )
                        WebDavItemClickAction.OpenVideo -> videoActions.openWebDavVideo(item)
                        WebDavItemClickAction.NoAction -> Unit
                    }
                },
                onAddToLibrary = comicActions::favoriteWebDavComic,
                onDownloadToLocal = downloadActions::downloadWebDavComic,
                onSelectFile = { item -> onSelectionChange(AppSelection.WebDavFile(item)) },
                onSaveDirectory = sourceActions::saveCurrentWebDavDirectory,
                showSaveDirectoryAction = state.isAddingWebDavPath,
                downloadProgress = state.downloadProgress,
                downloadError = state.localOpenError,
                actionMessage = state.actionMessage,
                onCancelDownload = downloadActions::cancelActiveDownload,
                onSearchQueryChange = webDavViewModel::updateSearchQuery,
                onSortFieldChange = webDavViewModel::updateSortField,
                onToggleSortDirection = webDavViewModel::toggleSortDirection,
                onRefresh = webDavViewModel::refreshCurrentDirectory,
                selectedFile = state.selection.webDavFileOrNull,
                modifier = modifier,
            )
        }
        shouldShowWebDavAccountForm(
            isAddingWebDavPath = state.isAddingWebDavPath,
            editingWebDavSourceId = state.editingWebDavSourceId,
            webDavStatus = state.webDavUiState.status,
        ) -> {
            WebDavAccountScreen(
                uiState = state.webDavUiState,
                onDisplayNameChange = webDavViewModel::updateDisplayName,
                onHostChange = webDavViewModel::updateHost,
                onPortChange = webDavViewModel::updatePort,
                onRootPathChange = webDavViewModel::updateRootPath,
                onUseHttpsChange = webDavViewModel::updateUseHttps,
                onAnonymousAccessChange = webDavViewModel::updateAnonymousAccess,
                onUsernameChange = webDavViewModel::updateUsername,
                onPasswordChange = webDavViewModel::updatePassword,
                onTestConnection = {
                    sourceActions.saveConnectedWebDavSource(state.editingWebDavSourceId)
                },
                onBackToLibrary = sourceActions::closeWebDav,
                message = state.localOpenError,
                modifier = modifier,
            )
        }
        else -> {
            LocalSourcesRoute(
                state = state,
                fileDirectoryViewModel = fileDirectoryViewModel,
                sourceActions = sourceActions,
                comicActions = comicActions,
                videoActions = videoActions,
                onChooseLocalDirectory = onChooseLocalDirectory,
                onSelectionChange = onSelectionChange,
                webDavMessage = state.webDavUiState.message.takeIf { it.isNotBlank() },
                modifier = modifier,
            )
        }
    }
}

@Composable
private fun LocalSourcesRoute(
    state: AppSourcesRouteState,
    fileDirectoryViewModel: FileDirectoryViewModel,
    sourceActions: AppSourceActions,
    comicActions: AppComicActions,
    videoActions: AppVideoActions,
    onChooseLocalDirectory: () -> Unit,
    onSelectionChange: (AppSelection) -> Unit,
    modifier: Modifier,
    webDavMessage: String? = null,
) {
    FileDirectoryTabContent(
        fileDirectoryUiState = state.fileDirectoryUiState,
        localOpenError = state.localOpenError,
        webDavMessage = webDavMessage,
        selectedDirectoryComic = state.selection.directoryComicOrNull,
        selectedDirectoryVideo = state.selection.directoryVideoOrNull,
        onAddLocalDirectory = onChooseLocalDirectory,
        onOpenWebDav = sourceActions::startAddingWebDavSource,
        onOpenLibrary = sourceActions::openLibrary,
        onOpenSource = sourceActions::openSource,
        onOpenDirectory = fileDirectoryViewModel::openLocalDirectory,
        onOpenComic = comicActions::openLocalDirectoryComic,
        onOpenVideo = videoActions::openLocalDirectoryVideo,
        onSelectComic = { item -> onSelectionChange(AppSelection.DirectoryComic(item)) },
        onSelectVideo = { item -> onSelectionChange(AppSelection.DirectoryVideo(item)) },
        onDismissMessage = sourceActions::dismissMessage,
        onSearchQueryChange = fileDirectoryViewModel::updateSearchQuery,
        onSortFieldChange = fileDirectoryViewModel::updateSortField,
        onToggleSortDirection = fileDirectoryViewModel::toggleSortDirection,
        onRefresh = fileDirectoryViewModel::refreshCurrentDirectory,
        onDeleteSource = sourceActions::deleteSource,
        onDeleteLocalSourceWithFiles = sourceActions::deleteLocalSourceWithFiles,
        onEditWebDavSource = sourceActions::editWebDavSource,
        modifier = modifier,
    )
}
