package com.example.comicdav

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.example.comicdav.core.model.settings.AppSettings
import com.example.comicdav.data.AppSettingsStore
import com.example.comicdav.data.ComicCacheAnalysis
import com.example.comicdav.data.ComicCacheCategory
import com.example.comicdav.data.filedirectory.FileDirectorySource
import com.example.comicdav.data.library.LibraryItemWithSources
import com.example.comicdav.data.videolibrary.VideoLibraryItemWithSources
import com.example.comicdav.feature.filedirectory.FileDirectoryBrowserItem
import com.example.comicdav.feature.filedirectory.FileDirectoryScreen
import com.example.comicdav.feature.filedirectory.FileDirectoryUiState
import com.example.comicdav.feature.directorylisting.DirectorySortField
import com.example.comicdav.feature.library.LibraryScreen
import com.example.comicdav.feature.library.LibraryUiState
import com.example.comicdav.feature.reader.ReaderScreen
import com.example.comicdav.feature.reader.ReaderUiState
import com.example.comicdav.feature.settings.SettingsAction
import com.example.comicdav.feature.settings.SettingsScreen
import com.example.comicdav.feature.videolibrary.VideoLibraryScreen
import com.example.comicdav.feature.videolibrary.VideoLibraryUiState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

@Composable
internal fun ReaderRoute(
    readerUiState: ReaderUiState,
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
        onPageChanged = onPageChanged,
        onPageDemanded = onPageDemanded,
        onImageLoadStarted = onImageLoadStarted,
        onImageLoadSucceeded = onImageLoadSucceeded,
        onImageLoadFailed = onImageLoadFailed,
        onChooseLogFile = onChooseLogFile,
        loadingProgress = downloadProgress?.toReaderLoadingProgress(),
        onCancelLoading = onCancelLoading,
        onClose = onClose,
        readingDirection = appSettings.readingDirection,
        autoPageEnabled = appSettings.autoPageEnabled,
        onAutoPageEnabledChange = onAutoPageEnabledChange,
        autoPageIntervalMillis = appSettings.autoPageSpeedMillis.toLong(),
        volumeKeysTurnPages = appSettings.volumeKeysTurnPagesEnabled,
        pinchZoomEnabled = appSettings.readerPinchZoomEnabled,
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
    onOpenLibrary: () -> Unit,
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
        onOpenLibrary = onOpenLibrary,
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
    onClearCacheCategory: (ComicCacheCategory) -> Unit,
    onClearAllCache: () -> Unit,
    modifier: Modifier = Modifier,
) {
    SettingsScreen(
        settings = settings,
        onAction = { action ->
            dispatchSettingsAction(
                action = action,
                appSettingsStore = appSettingsStore,
                scope = scope,
                onClearCacheCategory = onClearCacheCategory,
                onClearAllCache = onClearAllCache,
            )
        },
        cacheAnalysis = cacheAnalysis,
        cacheActionMessage = cacheActionMessage,
        modifier = modifier,
    )
}

internal fun dispatchSettingsAction(
    action: SettingsAction,
    appSettingsStore: AppSettingsStore,
    scope: CoroutineScope,
    onClearCacheCategory: (ComicCacheCategory) -> Unit,
    onClearAllCache: () -> Unit,
) {
    when (action) {
        is SettingsAction.SetReadingDirection ->
            scope.launch { appSettingsStore.updateReadingDirection(action.value) }
        is SettingsAction.SetReaderLoggingMode ->
            scope.launch { appSettingsStore.updateReaderLoggingMode(action.value) }
        is SettingsAction.SetColorPalette ->
            scope.launch { appSettingsStore.updateColorPalette(action.value) }
        is SettingsAction.SetAvifImagesEnabled ->
            scope.launch { appSettingsStore.updateAvifImagesEnabled(action.value) }
        is SettingsAction.SetAutoPageEnabled ->
            scope.launch { appSettingsStore.updateAutoPageEnabled(action.value) }
        is SettingsAction.SetAutoPageSpeedMillis ->
            scope.launch { appSettingsStore.updateAutoPageSpeedMillis(action.value) }
        is SettingsAction.SetScreenRotationLockEnabled ->
            scope.launch { appSettingsStore.updateScreenRotationLockEnabled(action.value) }
        is SettingsAction.SetVolumeKeysTurnPagesEnabled ->
            scope.launch { appSettingsStore.updateVolumeKeysTurnPagesEnabled(action.value) }
        is SettingsAction.SetReaderPinchZoomEnabled ->
            scope.launch { appSettingsStore.updateReaderPinchZoomEnabled(action.value) }
        is SettingsAction.SetPageImageCacheEnabled ->
            scope.launch { appSettingsStore.updatePageImageCacheEnabled(action.value) }
        is SettingsAction.SetDiskCacheLimitMb ->
            scope.launch { appSettingsStore.updateDiskCacheLimitMb(action.value) }
        is SettingsAction.SetWebDavPrefetchPageCount ->
            scope.launch { appSettingsStore.updateWebDavPrefetchPageCount(action.value) }
        is SettingsAction.SetLibraryCoversEnabled ->
            scope.launch { appSettingsStore.updateLibraryCoversEnabled(action.value) }
        is SettingsAction.SetVideoResumeEnabled ->
            scope.launch { appSettingsStore.updateVideoResumeEnabled(action.value) }
        is SettingsAction.SetVideoBackgroundMode ->
            scope.launch { appSettingsStore.updateVideoBackgroundMode(action.value) }
        is SettingsAction.SetVideoSeekOptimizationEnabled ->
            scope.launch { appSettingsStore.updateVideoSeekOptimizationEnabled(action.value) }
        is SettingsAction.SetVideoForwardPrefetchMode ->
            scope.launch { appSettingsStore.updateVideoForwardPrefetchMode(action.value) }
        is SettingsAction.SetVideoProxyDiagnosticsMode ->
            scope.launch { appSettingsStore.updateVideoProxyDiagnosticsMode(action.value) }
        is SettingsAction.SetVideoPlayerProxyDebugInfoEnabled ->
            scope.launch { appSettingsStore.updateVideoPlayerProxyDebugInfoEnabled(action.value) }
        is SettingsAction.SetVideoOutputMode ->
            scope.launch { appSettingsStore.updateVideoOutputMode(action.value) }
        is SettingsAction.SetGpuApiMode ->
            scope.launch { appSettingsStore.updateGpuApiMode(action.value) }
        is SettingsAction.SetAnime4KEnabled ->
            scope.launch { appSettingsStore.updateAnime4KEnabled(action.value) }
        is SettingsAction.SetAnime4KMode ->
            scope.launch { appSettingsStore.updateAnime4KMode(action.value) }
        is SettingsAction.SetAnime4KQuality ->
            scope.launch { appSettingsStore.updateAnime4KQuality(action.value) }
        is SettingsAction.SetVideoDecoderMode ->
            scope.launch { appSettingsStore.updateVideoDecoderMode(action.value) }
        is SettingsAction.SetMpvProfileMode ->
            scope.launch { appSettingsStore.updateMpvProfileMode(action.value) }
        is SettingsAction.SetVideoControlsAutoHideMillis ->
            scope.launch { appSettingsStore.updateVideoControlsAutoHideMillis(action.value) }
        is SettingsAction.SetVideoPlayerOrientationMode ->
            scope.launch { appSettingsStore.updateVideoPlayerOrientationMode(action.value) }
        is SettingsAction.SetVideoLibraryThumbnailsEnabled ->
            scope.launch { appSettingsStore.updateVideoLibraryThumbnailsEnabled(action.value) }
        is SettingsAction.ClearCacheCategory -> onClearCacheCategory(action.category)
        SettingsAction.ClearAllCache -> onClearAllCache()
    }
}
