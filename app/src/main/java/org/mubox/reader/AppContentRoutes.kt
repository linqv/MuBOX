package org.mubox.reader

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import org.mubox.reader.core.model.settings.AppSettings
import org.mubox.reader.core.model.history.WatchHistoryEntry
import org.mubox.reader.data.AppSettingsStore
import org.mubox.reader.core.model.cache.ComicCacheAnalysis
import org.mubox.reader.core.model.cache.ComicCacheCategory
import org.mubox.reader.core.model.source.FileDirectorySource
import org.mubox.reader.feature.filedirectory.FileDirectoryBrowserItem
import org.mubox.reader.feature.filedirectory.FileDirectoryScreen
import org.mubox.reader.feature.filedirectory.FileDirectoryUiState
import org.mubox.reader.ui.directorylisting.DirectorySortField
import org.mubox.reader.feature.reader.ReaderScreen
import org.mubox.reader.feature.reader.ReaderUiState
import org.mubox.reader.feature.settings.SettingsScreen
import kotlinx.coroutines.CoroutineScope

@Composable
internal fun ReaderRoute(
    readerUiState: ReaderUiState,
    localOpenError: String?,
    downloadProgress: org.mubox.reader.core.model.transfer.TransferProgress?,
    appSettings: AppSettings,
    readerLandscapeModeEnabled: Boolean = false,
    readerLandscapeOrientationLocked: Boolean = false,
    onReaderLandscapeModeChange: (Boolean) -> Unit = {},
    onReaderLandscapeOrientationLockedChange: (Boolean) -> Unit = {},
    onPageChanged: (Int) -> Unit,
    onPageDemanded: (Int, String) -> Unit,
    onCancelLoading: () -> Unit,
    onClose: () -> Unit,
    onAutoPageEnabledChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    ReaderScreen(
        uiState = readerUiState.copy(error = readerUiState.error ?: localOpenError),
        onPageChanged = onPageChanged,
        onPageDemanded = onPageDemanded,
        loadingProgress = downloadProgress?.toReaderLoadingProgress(),
        onCancelLoading = onCancelLoading,
        onClose = onClose,
        readingDirection = appSettings.reader.readingDirection,
        autoPageEnabled = appSettings.reader.autoPageEnabled,
        onAutoPageEnabledChange = onAutoPageEnabledChange,
        autoPageIntervalMillis = appSettings.reader.autoPageSpeedMillis.toLong(),
        volumeKeysTurnPages = appSettings.reader.volumeKeysTurnPagesEnabled,
        pinchZoomEnabled = appSettings.reader.readerPinchZoomEnabled,
        readerLandscapeModeEnabled = readerLandscapeModeEnabled,
        readerLandscapeOrientationLocked = readerLandscapeOrientationLocked,
        onReaderLandscapeModeChange = onReaderLandscapeModeChange,
        onReaderLandscapeOrientationLockedChange = onReaderLandscapeOrientationLockedChange,
        modifier = modifier,
    )
}

@Composable
internal fun FileDirectoryTabContent(
    fileDirectoryUiState: FileDirectoryUiState,
    localOpenError: String?,
    webDavMessage: String?,
    selectedDirectoryComic: FileDirectoryBrowserItem?,
    selectedDirectoryVideo: FileDirectoryBrowserItem?,
    onAddLocalDirectory: () -> Unit,
    onOpenWebDav: () -> Unit,
    onOpenSource: (FileDirectorySource) -> Unit,
    onOpenDirectory: (FileDirectoryBrowserItem) -> Unit,
    onOpenComic: (FileDirectoryBrowserItem) -> Unit,
    onOpenVideo: (FileDirectoryBrowserItem) -> Unit,
    onSelectComic: (FileDirectoryBrowserItem) -> Unit,
    onSelectVideo: (FileDirectoryBrowserItem) -> Unit,
    onDismissMessage: () -> Unit,
    onSearchQueryChange: (String) -> Unit,
    onSortFieldChange: (DirectorySortField) -> Unit,
    onToggleSortDirection: () -> Unit,
    onToggleViewMode: () -> Unit,
    gridVideoThumbnailsEnabled: Boolean,
    onRequestVideoThumbnail: suspend (FileDirectoryBrowserItem) -> Unit,
    onRefresh: () -> Unit,
    onDeleteSource: (FileDirectorySource) -> Unit,
    onDeleteLocalSourceWithFiles: (FileDirectorySource) -> Unit,
    onEditWebDavSource: (FileDirectorySource) -> Unit,
    modifier: Modifier = Modifier,
) {
    FileDirectoryScreen(
        uiState = fileDirectoryUiState.copy(
            error = fileDirectoryUiState.error ?: localOpenError ?: webDavMessage,
        ),
        onAddLocalDirectory = onAddLocalDirectory,
        onOpenWebDav = onOpenWebDav,
        onOpenSource = onOpenSource,
        onOpenDirectory = onOpenDirectory,
        onOpenComic = onOpenComic,
        onOpenVideo = onOpenVideo,
        onSelectComic = onSelectComic,
        onSelectVideo = onSelectVideo,
        onDismissMessage = onDismissMessage,
        onSearchQueryChange = onSearchQueryChange,
        onSortFieldChange = onSortFieldChange,
        onToggleSortDirection = onToggleSortDirection,
        onToggleViewMode = onToggleViewMode,
        gridVideoThumbnailsEnabled = gridVideoThumbnailsEnabled,
        onRequestVideoThumbnail = onRequestVideoThumbnail,
        onRefresh = onRefresh,
        onDeleteSource = onDeleteSource,
        onDeleteLocalSourceWithFiles = onDeleteLocalSourceWithFiles,
        onEditWebDavSource = onEditWebDavSource,
        selectedComic = selectedDirectoryComic,
        selectedVideo = selectedDirectoryVideo,
        modifier = modifier,
    )
}

@Composable
internal fun SettingsTabContent(
    settings: AppSettings,
    appSettingsStore: AppSettingsStore,
    scope: CoroutineScope,
    cacheAnalysis: ComicCacheAnalysis,
    cacheActionMessage: String?,
    history: List<WatchHistoryEntry>,
    onOpenHistoryEntry: (WatchHistoryEntry) -> Unit,
    onDeleteHistoryEntry: (WatchHistoryEntry) -> Unit,
    onClearHistory: () -> Unit,
    onClearCacheCategory: (ComicCacheCategory) -> Unit,
    onClearAllCache: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val actionHandler = SettingsActionHandler(
        appSettingsStore = appSettingsStore,
        scope = scope,
        onClearCacheCategory = onClearCacheCategory,
        onClearAllCache = onClearAllCache,
        onDeleteHistoryEntry = onDeleteHistoryEntry,
        onClearHistory = onClearHistory,
    )
    SettingsScreen(
        settings = settings,
        onAction = actionHandler::handle,
        history = history,
        onOpenHistoryEntry = onOpenHistoryEntry,
        cacheAnalysis = cacheAnalysis,
        cacheActionMessage = cacheActionMessage,
        modifier = modifier,
    )
}
