package com.example.comicdav

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.example.comicdav.data.AppSettings
import com.example.comicdav.data.AppSettingsStore
import com.example.comicdav.data.ComicCacheAnalysis
import com.example.comicdav.data.ComicCacheCategory
import com.example.comicdav.data.filedirectory.FileDirectorySourceEntity
import com.example.comicdav.data.library.LibraryItemWithSources
import com.example.comicdav.data.videolibrary.VideoLibraryItemWithSources
import com.example.comicdav.feature.filedirectory.FileDirectoryBrowserItem
import com.example.comicdav.feature.filedirectory.FileDirectoryScreen
import com.example.comicdav.feature.filedirectory.FileDirectoryUiState
import com.example.comicdav.feature.library.LibraryScreen
import com.example.comicdav.feature.library.LibraryUiState
import com.example.comicdav.feature.reader.ReaderScreen
import com.example.comicdav.feature.reader.ReaderUiState
import com.example.comicdav.feature.settings.SettingsScreen
import com.example.comicdav.feature.videolibrary.VideoLibraryScreen
import com.example.comicdav.feature.videolibrary.VideoLibraryUiState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

@Composable
internal fun ReaderRoute(
    readerUiState: ReaderUiState,
    localOpenError: String?,
    downloadProgress: com.example.comicdav.feature.webdav.DownloadProgressUi?,
    appSettings: AppSettings,
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
    onOpenSource: (FileDirectorySourceEntity) -> Unit,
    onOpenDirectory: (FileDirectoryBrowserItem) -> Unit,
    onOpenComic: (FileDirectoryBrowserItem) -> Unit,
    onOpenVideo: (FileDirectoryBrowserItem) -> Unit,
    onSelectComic: (FileDirectoryBrowserItem) -> Unit,
    onSelectVideo: (FileDirectoryBrowserItem) -> Unit,
    onGoUp: () -> Unit,
    onCloseBrowser: () -> Unit,
    onDismissMessage: () -> Unit,
    onDeleteSource: (FileDirectorySourceEntity) -> Unit,
    onDeleteLocalSourceWithFiles: (FileDirectorySourceEntity) -> Unit,
    onEditWebDavSource: (FileDirectorySourceEntity) -> Unit,
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
        onGoUp = onGoUp,
        onCloseBrowser = onCloseBrowser,
        onDismissMessage = onDismissMessage,
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
    modifier: Modifier = Modifier,
) {
    SettingsScreen(
        settings = settings,
        onReadingDirectionChange = { value ->
            scope.launch { appSettingsStore.updateReadingDirection(value) }
        },
        onReaderLoggingModeChange = { value ->
            scope.launch { appSettingsStore.updateReaderLoggingMode(value) }
        },
        onColorPaletteChange = { value ->
            scope.launch { appSettingsStore.updateColorPalette(value) }
        },
        onAvifImagesEnabledChange = { value ->
            scope.launch { appSettingsStore.updateAvifImagesEnabled(value) }
        },
        onAutoPageEnabledChange = { value ->
            scope.launch { appSettingsStore.updateAutoPageEnabled(value) }
        },
        onAutoPageSpeedChange = { value ->
            scope.launch { appSettingsStore.updateAutoPageSpeedMillis(value) }
        },
        onScreenRotationLockChange = { value ->
            scope.launch { appSettingsStore.updateScreenRotationLockEnabled(value) }
        },
        onVolumeKeysTurnPagesChange = { value ->
            scope.launch { appSettingsStore.updateVolumeKeysTurnPagesEnabled(value) }
        },
        onReaderPinchZoomEnabledChange = { value ->
            scope.launch { appSettingsStore.updateReaderPinchZoomEnabled(value) }
        },
        onPageImageCacheEnabledChange = { value ->
            scope.launch { appSettingsStore.updatePageImageCacheEnabled(value) }
        },
        onDiskCacheLimitChange = { value ->
            scope.launch { appSettingsStore.updateDiskCacheLimitMb(value) }
        },
        onWebDavPrefetchPageCountChange = { value ->
            scope.launch { appSettingsStore.updateWebDavPrefetchPageCount(value) }
        },
        onLibraryCoversEnabledChange = { value ->
            scope.launch { appSettingsStore.updateLibraryCoversEnabled(value) }
        },
        onVideoResumeEnabledChange = { value ->
            scope.launch { appSettingsStore.updateVideoResumeEnabled(value) }
        },
        onVideoSeekOptimizationEnabledChange = { value ->
            scope.launch { appSettingsStore.updateVideoSeekOptimizationEnabled(value) }
        },
        onVideoForwardPrefetchModeChange = { value ->
            scope.launch { appSettingsStore.updateVideoForwardPrefetchMode(value) }
        },
        onVideoProxyDiagnosticsModeChange = { value ->
            scope.launch { appSettingsStore.updateVideoProxyDiagnosticsMode(value) }
        },
        onVideoOutputModeChange = { value ->
            scope.launch { appSettingsStore.updateVideoOutputMode(value) }
        },
        onGpuApiModeChange = { value ->
            scope.launch { appSettingsStore.updateGpuApiMode(value) }
        },
        onVideoDecoderModeChange = { value ->
            scope.launch { appSettingsStore.updateVideoDecoderMode(value) }
        },
        onMpvProfileModeChange = { value ->
            scope.launch { appSettingsStore.updateMpvProfileMode(value) }
        },
        onVideoControlsAutoHideMillisChange = { value ->
            scope.launch { appSettingsStore.updateVideoControlsAutoHideMillis(value) }
        },
        onVideoPlayerOrientationModeChange = { value ->
            scope.launch { appSettingsStore.updateVideoPlayerOrientationMode(value) }
        },
        onVideoLibraryThumbnailsEnabledChange = { value ->
            scope.launch { appSettingsStore.updateVideoLibraryThumbnailsEnabled(value) }
        },
        cacheAnalysis = cacheAnalysis,
        cacheActionMessage = cacheActionMessage,
        onClearCacheCategory = onClearCacheCategory,
        modifier = modifier,
    )
}
