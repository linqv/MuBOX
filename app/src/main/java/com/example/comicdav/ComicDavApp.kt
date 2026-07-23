package com.example.comicdav

import android.app.Activity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.comicdav.core.model.settings.AppSettings
import com.example.comicdav.feature.reader.ReaderDiagnosticLog
import com.example.comicdav.feature.downloads.DownloadsScreen
import com.example.comicdav.feature.downloads.activeProgress
import com.example.comicdav.feature.webdav.WEB_DAV_STATUS_CONNECTED
import com.example.comicdav.ui.ComicDavTheme

@Composable
internal fun ComicDavApp(container: AppContainer) {
    val appViewModels = rememberAppViewModels(container)
    val webDavViewModel = appViewModels.webDav
    val readerViewModel = appViewModels.reader
    val libraryViewModel = appViewModels.library
    val videoLibraryViewModel = appViewModels.videoLibrary
    val fileDirectoryViewModel = appViewModels.fileDirectory
    val downloadCoordinator = appViewModels.downloads
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    val uiState = webDavViewModel.uiState
    val libraryUiState = libraryViewModel.uiState
    val videoLibraryUiState = videoLibraryViewModel.uiState
    val fileDirectoryUiState = fileDirectoryViewModel.uiState
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    val ui = rememberAppUiStateHolder(context)
    val dataFolderStore = container.dataFolderStore
    val appSettingsStore = container.appSettingsStore
    val webDavAccountStore = container.webDavAccountStore
    val downloadRecordStore = container.downloadRecordStore
    val videoDownloadStore = container.videoDownloadStore
    val downloadState by downloadCoordinator.state.collectAsState()
    val downloadProgress = downloadState.activeProgress
    val appSettings by appSettingsStore.settings.collectAsState(initial = AppSettings(videoResumeEnabled = false))
    val downloadRecords by downloadRecordStore.records.collectAsState(initial = emptyList())
    val videoDownloadRecords by videoDownloadStore.records.collectAsState(initial = emptyList())
    val actions = rememberAppActionGraph(
        context = context,
        scope = scope,
        settings = appSettings,
        container = container,
        viewModels = appViewModels,
        ui = ui,
    )
    val cacheActions = actions.cache
    val downloadActions = actions.downloads
    val sourceActions = actions.sources
    val activityLaunchers = actions.launchers
    val readerActions = actions.reader
    val videoActions = actions.video
    val comicActions = actions.comic
    LaunchedEffect(downloadState) {
        downloadActions.handleState(downloadState)
    }
    LaunchedEffect(context.cacheDir) {
        cacheActions.refreshNow()
    }
    LaunchedEffect(dataFolderStore) {
        ui.dataFolderUriText = dataFolderStore.loadFolderUri()
        if (ui.logFolderUriText.isNullOrBlank()) {
            ui.logFolderUriText = ui.dataFolderUriText
        }
        ui.isDataFolderLoading = false
    }

    ReaderOrientationEffects(
        activity = context as? Activity,
        lifecycleOwner = lifecycleOwner,
        screenRotationLockEnabled = appSettings.screenRotationLockEnabled,
        readerOpenState = ui.readerOpenState,
        readerLandscapeModeState = ui.readerLandscapeModeState,
        readerLandscapeOrientationLockedState = ui.readerLandscapeOrientationLockedState,
        forceMainPortraitState = ui.forceMainPortraitState,
        configurationOrientation = configuration.orientation,
    )

    LaunchedEffect(appSettings.readerLoggingMode) {
        ReaderDiagnosticLog.setMode(appSettings.readerLoggingMode)
        if (!appSettings.loggingEnabled) {
            ReaderDiagnosticLog.clearSink()
        }
    }

    LaunchedEffect(appSettings.pageImageCacheEnabled, appSettings.diskCacheLimitMb) {
        cacheActions.applyReaderPageCacheSettings(
            pageImageCacheEnabled = appSettings.pageImageCacheEnabled,
            diskCacheLimitMb = appSettings.diskCacheLimitMb,
        )
    }

    LaunchedEffect(uiState.status, uiState.baseUrl, uiState.username, uiState.password) {
        if (uiState.status == WEB_DAV_STATUS_CONNECTED && uiState.baseUrl.isNotBlank()) {
            webDavAccountStore.saveAccount(
                baseUrl = uiState.baseUrl,
                username = uiState.username,
                password = uiState.password,
            )
        }
    }

    ComicDavBackHandler(
        hasActiveSelection = ui.hasActiveSelection,
        readerOpenState = ui.readerOpenState,
        isWebDavOpen = ui.isWebDavOpen,
        hasOpenFileDirectory = fileDirectoryUiState.currentTitle != null,
        selectedTab = ui.selectedTab,
        onClearSelection = ui::clearSelection,
        onCloseReader = readerActions::closeFromNavigation,
        onNavigateWebDavBack = webDavViewModel::handleBack,
        onCloseWebDav = sourceActions::closeWebDav,
        onNavigateFileDirectoryBack = { fileDirectoryViewModel.handleBack() },
        onReturnToSources = ui::returnToSources,
    )

    LaunchedEffect(ui.selectedTab) {
        if (ui.selectedTab == AppTab.SETTINGS) {
            cacheActions.refreshNow()
        }
    }

    ComicDavTheme(palette = appSettings.colorPalette) {
        Surface(modifier = Modifier.fillMaxSize()) {
            when {
                ui.isDataFolderLoading -> {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                }

                ui.dataFolderUriText.isNullOrBlank() -> {
                    DataFolderGateScreen(
                        onChooseFolder = activityLaunchers.chooseDataFolder,
                        modifier = Modifier.fillMaxSize(),
                    )
                }

                else -> {
                    ReaderOverlayHost(
                        readerOpenState = ui.readerOpenState,
                        readerContent = {
                            ReaderRoute(
                                readerUiState = readerViewModel.uiState,
                                localOpenError = ui.localOpenError,
                                downloadProgress = downloadProgress,
                                appSettings = appSettings,
                                readerLandscapeModeEnabled = ui.readerLandscapeModeState.value,
                                readerLandscapeOrientationLocked = ui.readerLandscapeOrientationLockedState.value,
                                onReaderLandscapeModeChange = readerActions::changeLandscapeMode,
                                onReaderLandscapeOrientationLockedChange =
                                    readerActions::changeLandscapeOrientationLocked,
                                onPageChanged = readerViewModel::selectPage,
                                onPageDemanded = readerViewModel::reportPageDemand,
                                onImageLoadStarted = readerViewModel::reportImageLoadStarted,
                                onImageLoadSucceeded = readerViewModel::reportImageLoadSucceeded,
                                onImageLoadFailed = readerViewModel::reportImageLoadFailed,
                                onChooseLogFile = readerActions::chooseLogFolder,
                                onCancelLoading = readerActions::cancelLoading,
                                onClose = readerActions::close,
                                onAutoPageEnabledChange = readerActions::updateAutoPageEnabled,
                            )
                        },
                    ) {
                        ComicDavAppShell(
                            selectedTab = ui.selectedTab,
                            onTabSelected = ui::selectTab,
                            bottomBar = selectionBottomBar(
                                selectedWebDavFile = ui.selectedWebDavFile,
                                selectedDirectoryComic = ui.selectedDirectoryComic,
                                selectedDirectoryVideo = ui.selectedDirectoryVideo,
                                selectedLibraryItem = ui.selectedLibraryItem,
                                selectedVideoLibraryItem = ui.selectedVideoLibraryItem,
                                onDownloadWebDavFile = { item ->
                                    ui.clearSelection()
                                    downloadActions.downloadWebDavComic(item)
                                },
                                onDownloadWebDavVideo = { item ->
                                    ui.clearSelection()
                                    downloadActions.downloadWebDavVideo(item)
                                },
                                onAddWebDavFileToLibrary = { item ->
                                    ui.clearSelection()
                                    comicActions.favoriteWebDavComic(item)
                                },
                                onAddWebDavVideoToVideoLibrary = { item ->
                                    ui.clearSelection()
                                    videoActions.favoriteWebDavVideo(item)
                                },
                                onAddDirectoryComicToLibrary = { item ->
                                    ui.clearSelection()
                                    comicActions.favoriteLocalDirectoryComic(item)
                                },
                                onAddDirectoryVideoToVideoLibrary = { item ->
                                    ui.clearSelection()
                                    videoActions.favoriteLocalDirectoryVideo(item)
                                },
                                onRemoveLibraryItem = comicActions::removeLibraryItem,
                                onRefreshLibraryCover = comicActions::refreshLibraryCover,
                                onDownloadLibraryItem = { item ->
                                    ui.clearSelectionIf { it is AppSelection.LibraryItem }
                                    downloadActions.downloadLibraryWebDavComic(item)
                                },
                                onRemoveVideoLibraryItem = videoActions::removeVideoLibraryItem,
                                onRefreshVideoLibraryThumbnail = videoActions::refreshVideoLibraryThumbnail,
                                onDeleteVideoLibraryThumbnail = videoActions::deleteVideoLibraryThumbnail,
                                onCancel = ui::clearSelection,
                            ),
                        ) { contentModifier ->
                            when (ui.selectedTab) {
                                AppTab.SOURCES -> AppSourcesRoute(
                                    state = AppSourcesRouteState(
                                        webDavUiState = uiState,
                                        fileDirectoryUiState = fileDirectoryUiState,
                                        isWebDavOpen = ui.isWebDavOpen,
                                        isAddingWebDavPath = ui.isAddingWebDavPath,
                                        editingWebDavSourceId = ui.editingWebDavSourceId,
                                        localOpenError = ui.localOpenError,
                                        actionMessage = ui.webDavActionMessage,
                                        downloadProgress = downloadProgress,
                                        selection = ui.selection,
                                    ),
                                    webDavViewModel = webDavViewModel,
                                    fileDirectoryViewModel = fileDirectoryViewModel,
                                    sourceActions = sourceActions,
                                    comicActions = comicActions,
                                    videoActions = videoActions,
                                    downloadActions = downloadActions,
                                    onChooseLocalDirectory = activityLaunchers.chooseLocalDirectory,
                                    onSelectionChange = { selection -> ui.selection = selection },
                                    modifier = contentModifier,
                                )
                                AppTab.LIBRARY -> {
                                    LibraryTabContent(
                                        libraryUiState = libraryUiState,
                                        localOpenError = ui.localOpenError,
                                        onOpenItem = comicActions::openLibraryItem,
                                        onSelectItem = { item ->
                                            ui.selection = AppSelection.LibraryItem(item)
                                        },
                                        onOpenDirectories = {
                                            ui.localOpenError = null
                                            ui.selectedTabName = AppTab.SOURCES.name
                                        },
                                        onDismissMessage = {
                                            ui.localOpenError = null
                                            libraryViewModel.clearMessage()
                                        },
                                        coversEnabled = appSettings.libraryCoversEnabled,
                                        selectedItemId = ui.selectedLibraryItem?.item?.id,
                                        modifier = contentModifier,
                                    )
                                }
                                AppTab.VIDEO_LIBRARY -> {
                                    VideoLibraryTabContent(
                                        videoLibraryUiState = videoLibraryUiState,
                                        localOpenError = ui.localOpenError,
                                        onOpenItem = videoActions::openVideoLibraryItem,
                                        onSelectItem = { item ->
                                            ui.selection = AppSelection.VideoLibraryItem(item)
                                        },
                                        onOpenDirectories = {
                                            ui.localOpenError = null
                                            ui.selectedTabName = AppTab.SOURCES.name
                                        },
                                        onDismissMessage = {
                                            ui.localOpenError = null
                                            videoLibraryViewModel.clearMessage()
                                        },
                                        thumbnailsEnabled = appSettings.videoLibraryThumbnailsEnabled,
                                        selectedItemId = ui.selectedVideoLibraryItem?.item?.id,
                                        modifier = contentModifier,
                                    )
                                }
                                AppTab.DOWNLOADS -> {
                                    DownloadsScreen(
                                        comicDownloads = downloadRecords,
                                        videoDownloads = videoDownloadRecords,
                                        activeDownload = downloadProgress,
                                        onOpenComicDownload = comicActions::openDownloadRecordComic,
                                        onPlayVideoDownload = videoActions::playVideoDownloadRecord,
                                        onCancelActiveDownload = downloadActions::cancelActiveDownload,
                                        onRemoveComicRecord = downloadActions::removeComicRecord,
                                        onRemoveVideoRecord = downloadActions::removeVideoRecord,
                                        onDeleteComicFile = downloadActions::deleteComicFile,
                                        onDeleteVideoFile = downloadActions::deleteVideoFile,
                                        onShowDetails = {
                                            ui.localOpenError = null
                                            ui.webDavActionMessage = "暂无详情页面"
                                        },
                                        onOpenSources = {
                                            ui.localOpenError = null
                                            ui.selectedTabName = AppTab.SOURCES.name
                                        },
                                        actionMessage = ui.localOpenError ?: ui.webDavActionMessage,
                                        modifier = contentModifier,
                                    )
                                }
                                AppTab.SETTINGS -> {
                                    SettingsTabContent(
                                        settings = appSettings,
                                        appSettingsStore = appSettingsStore,
                                        scope = scope,
                                        cacheAnalysis = ui.cacheAnalysis,
                                        cacheActionMessage = ui.cacheActionMessage,
                                        onClearCacheCategory = cacheActions::clearCategory,
                                        onClearAllCache = cacheActions::clearAll,
                                        modifier = contentModifier,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
