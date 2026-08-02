package com.example.comicdav

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.example.comicdav.core.diagnostics.Diagnostics
import com.example.comicdav.core.model.settings.AppSettings
import com.example.comicdav.core.model.history.WatchHistoryEntry
import com.example.comicdav.data.AppSettingsStore
import com.example.comicdav.core.model.cache.ComicCacheAnalysis
import com.example.comicdav.core.model.cache.ComicCacheCategory
import com.example.comicdav.core.model.source.FileDirectorySource
import com.example.comicdav.core.model.library.LibraryItemWithSources
import com.example.comicdav.core.model.videolibrary.VideoLibraryItemWithSources
import com.example.comicdav.feature.filedirectory.FileDirectoryBrowserItem
import com.example.comicdav.feature.filedirectory.FileDirectoryScreen
import com.example.comicdav.feature.filedirectory.FileDirectoryUiState
import com.example.comicdav.ui.directorylisting.DirectorySortField
import com.example.comicdav.feature.library.LibraryScreen
import com.example.comicdav.feature.library.LibraryUiState
import com.example.comicdav.feature.reader.ReaderScreen
import com.example.comicdav.feature.reader.ReaderUiState
import com.example.comicdav.feature.settings.SettingsScreen
import com.example.comicdav.feature.videolibrary.VideoLibraryScreen
import com.example.comicdav.feature.videolibrary.VideoLibraryUiState
import kotlinx.coroutines.CoroutineScope

@Composable
internal fun ReaderRoute(
    readerUiState: ReaderUiState,
    diagnostics: Diagnostics,
    localOpenError: String?,
    downloadProgress: com.example.comicdav.core.model.transfer.TransferProgress?,
    appSettings: AppSettings,
    readerLandscapeModeEnabled: Boolean = false,
    readerLandscapeOrientationLocked: Boolean = false,
    onReaderLandscapeModeChange: (Boolean) -> Unit = {},
    onReaderLandscapeOrientationLockedChange: (Boolean) -> Unit = {},
    onPageChanged: (Int) -> Unit,
    onPageDemanded: (Int, String) -> Unit,
    onImageLoadStarted: (Int) -> Unit,
    onImageLoadSucceeded: (Int) -> Unit,
    onImageLoadFailed: (Int) -> Unit,
    onChooseLogFile: () -> Unit,
    onCancelLoading: () -> Unit,
    onClose: () -> Unit,
    onAutoPageEnabledChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    ReaderScreen(
        uiState = readerUiState.copy(error = readerUiState.error ?: localOpenError),
        diagnostics = diagnostics,
        onPageChanged = onPageChanged,
        onPageDemanded = onPageDemanded,
        onImageLoadStarted = onImageLoadStarted,
        onImageLoadSucceeded = onImageLoadSucceeded,
        onImageLoadFailed = onImageLoadFailed,
        onChooseLogFile = onChooseLogFile,
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
internal fun LibraryTabContent(
    libraryUiState: LibraryUiState,
    localOpenError: String?,
    coversEnabled: Boolean,
    selectedItemId: Long?,
    onOpenItem: (LibraryItemWithSources) -> Unit,
    onSelectItem: (LibraryItemWithSources) -> Unit,
    onOpenDirectories: () -> Unit,
    onDismissMessage: () -> Unit,
    navigationIcon: (@Composable () -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    LibraryScreen(
        uiState = libraryUiState.copy(error = libraryUiState.error ?: localOpenError),
        onOpenItem = onOpenItem,
        onSelectItem = onSelectItem,
        onOpenDirectories = onOpenDirectories,
        onDismissMessage = onDismissMessage,
        coversEnabled = coversEnabled,
        selectedItemId = selectedItemId,
        navigationIcon = navigationIcon,
        modifier = modifier,
    )
}

@Composable
internal fun VideoLibraryTabContent(
    videoLibraryUiState: VideoLibraryUiState,
    localOpenError: String?,
    thumbnailsEnabled: Boolean,
    selectedItemId: Long?,
    onOpenItem: (VideoLibraryItemWithSources) -> Unit,
    onSelectItem: (VideoLibraryItemWithSources) -> Unit,
    onOpenDirectories: () -> Unit,
    onDismissMessage: () -> Unit,
    navigationIcon: (@Composable () -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    VideoLibraryScreen(
        uiState = videoLibraryUiState.copy(error = videoLibraryUiState.error ?: localOpenError),
        onOpenItem = onOpenItem,
        onSelectItem = onSelectItem,
        onOpenDirectories = onOpenDirectories,
        onDismissMessage = onDismissMessage,
        thumbnailsEnabled = thumbnailsEnabled,
        selectedItemId = selectedItemId,
        navigationIcon = navigationIcon,
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
