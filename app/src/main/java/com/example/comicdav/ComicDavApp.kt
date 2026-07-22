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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.comicdav.data.AppSettings
import com.example.comicdav.data.ComicCacheAnalysis
import com.example.comicdav.data.ReaderLoggingMode
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
    val readerOpenState = rememberSaveable { mutableStateOf(false) }
    val readerLandscapeModeState = rememberSaveable { mutableStateOf(false) }
    val readerLandscapeOrientationLockedState = rememberSaveable { mutableStateOf(false) }
    val forceMainPortraitState = rememberSaveable { mutableStateOf(false) }
    var isWebDavOpen by rememberSaveable { mutableStateOf(false) }
    var isAddingWebDavPath by rememberSaveable { mutableStateOf(false) }
    var editingWebDavSourceId by rememberSaveable { mutableStateOf<Long?>(null) }
    var selectedTabName by rememberSaveable { mutableStateOf(AppTab.SOURCES.name) }
    val selectedTab = remember(selectedTabName) {
        runCatching { AppTab.valueOf(selectedTabName) }.getOrDefault(AppTab.SOURCES)
    }
    var appSelection by remember { mutableStateOf<AppSelection>(AppSelection.None) }
    val selectedWebDavFile = appSelection.webDavFileOrNull
    val selectedDirectoryComic = appSelection.directoryComicOrNull
    val selectedDirectoryVideo = appSelection.directoryVideoOrNull
    val selectedLibraryItem = appSelection.libraryItemOrNull
    val selectedVideoLibraryItem = appSelection.videoLibraryItemOrNull
    var localOpenError by remember { mutableStateOf<String?>(null) }
    var webDavActionMessage by remember { mutableStateOf<String?>(null) }
    var cacheAnalysis by remember { mutableStateOf(ComicCacheAnalysis()) }
    var cacheActionMessage by remember { mutableStateOf<String?>(null) }
    var logFolderUriText by rememberSaveable { mutableStateOf(loadReaderLogFolderUri(context)) }
    var dataFolderUriText by rememberSaveable { mutableStateOf<String?>(null) }
    var isDataFolderLoading by remember { mutableStateOf(true) }
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
    val cacheActions = AppCacheActions(
        context = context,
        scope = scope,
        viewModels = appViewModels,
        callbacks = AppCacheActionCallbacks(
            setAnalysis = { analysis -> cacheAnalysis = analysis },
            setActionMessage = { message -> cacheActionMessage = message },
        ),
    )
    val downloadActions = AppDownloadActions(
        context = context,
        scope = scope,
        dataFolderUri = dataFolderUriText,
        container = container,
        viewModels = appViewModels,
        callbacks = AppDownloadActionCallbacks(
            setError = { message -> localOpenError = message },
            setActionMessage = { message -> webDavActionMessage = message },
            clearSelectionIf = { predicate -> appSelection = appSelection.clearIf(predicate) },
        ),
    )
    fun clearSelection() {
        appSelection = appSelection.clear()
    }
    LaunchedEffect(downloadState) {
        downloadActions.handleState(downloadState)
    }
    LaunchedEffect(context.cacheDir) {
        cacheActions.refreshNow()
    }
    LaunchedEffect(dataFolderStore) {
        dataFolderUriText = dataFolderStore.loadFolderUri()
        if (logFolderUriText.isNullOrBlank()) {
            logFolderUriText = dataFolderUriText
        }
        isDataFolderLoading = false
    }
    val sourceActions = AppSourceActions(
        context = context,
        scope = scope,
        container = container,
        viewModels = appViewModels,
        callbacks = AppSourceActionCallbacks(
            setError = { message -> localOpenError = message },
            setActionMessage = { message -> webDavActionMessage = message },
            setWebDavOpen = { open -> isWebDavOpen = open },
            setAddingWebDavPath = { adding -> isAddingWebDavPath = adding },
            setEditingWebDavSourceId = { sourceId -> editingWebDavSourceId = sourceId },
            selectTab = { tab -> selectedTabName = tab.name },
        ),
    )
    val activityLaunchers = rememberAppActivityLaunchers(
        context = context,
        scope = scope,
        dataFolderStore = dataFolderStore,
        loggingEnabled = appSettings.readerLoggingMode != ReaderLoggingMode.OFF,
        onDataFolderSelected = { uriText ->
            dataFolderUriText = uriText
            if (logFolderUriText.isNullOrBlank()) {
                logFolderUriText = uriText
            }
        },
        onLogFolderSelected = { uriText -> logFolderUriText = uriText },
        onLocalDirectorySelected = sourceActions::addLocalDirectory,
        onVideoPlayerClosed = { forceMainPortraitState.value = true },
    )
    val readerActions = AppReaderActions(
        scope = scope,
        settings = appSettings,
        appSettingsStore = appSettingsStore,
        activityLaunchers = activityLaunchers,
        viewModels = appViewModels,
        callbacks = AppReaderActionCallbacks(
            isLandscapeModeEnabled = { readerLandscapeModeState.value },
            setLandscapeModeEnabled = { enabled -> readerLandscapeModeState.value = enabled },
            setLandscapeOrientationLocked = { locked ->
                readerLandscapeOrientationLockedState.value = locked
            },
            setReaderOpen = { open -> readerOpenState.value = open },
            setForceMainPortrait = { force -> forceMainPortraitState.value = force },
            setActionMessage = { message -> webDavActionMessage = message },
        ),
    )
    val webDavResolver = AppWebDavResolver(
        loadSavedAccount = webDavAccountStore::loadAccount,
        loadSavedClient = container.webDavClientProvider::clientFor,
        activeConnection = {
            val latestUiState = webDavViewModel.uiState
            ActiveWebDavConnection(
                activeAccountId = webDavViewModel.activeAccountId(),
                configuredAccountId = webDavViewModel.accountId(),
                baseUrl = latestUiState.baseUrl,
                username = latestUiState.username,
                password = latestUiState.password,
                client = webDavViewModel.activeClient(),
            )
        },
    )
    val videoActions = AppVideoActions(
        context = context,
        scope = scope,
        settings = appSettings,
        container = container,
        viewModels = appViewModels,
        webDavResolver = webDavResolver,
        callbacks = AppVideoActionCallbacks(
            launchPlayer = activityLaunchers.openVideoPlayer,
            setError = { message -> localOpenError = message },
            setActionMessage = { message -> webDavActionMessage = message },
            clearSelectionIf = { predicate -> appSelection = appSelection.clearIf(predicate) },
        ),
    )
    val comicActions = AppComicActions(
        context = context,
        scope = scope,
        settings = appSettings,
        logFolderUri = logFolderUriText,
        container = container,
        viewModels = appViewModels,
        webDavResolver = webDavResolver,
        callbacks = AppComicActionCallbacks(
            setError = { message -> localOpenError = message },
            setActionMessage = { message -> webDavActionMessage = message },
            setWebDavOpen = { open -> isWebDavOpen = open },
            setReaderOpen = { open -> readerOpenState.value = open },
            clearSelectionIf = { predicate -> appSelection = appSelection.clearIf(predicate) },
            refreshCacheAnalysis = cacheActions::refresh,
        ),
    )

    ReaderOrientationEffects(
        activity = context as? Activity,
        lifecycleOwner = lifecycleOwner,
        screenRotationLockEnabled = appSettings.screenRotationLockEnabled,
        readerOpenState = readerOpenState,
        readerLandscapeModeState = readerLandscapeModeState,
        readerLandscapeOrientationLockedState = readerLandscapeOrientationLockedState,
        forceMainPortraitState = forceMainPortraitState,
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

    val hasActiveSelection = appSelection.isActive

    ComicDavBackHandler(
        hasActiveSelection = hasActiveSelection,
        readerOpenState = readerOpenState,
        isWebDavOpen = isWebDavOpen,
        hasOpenFileDirectory = fileDirectoryUiState.currentTitle != null,
        selectedTab = selectedTab,
        onClearSelection = ::clearSelection,
        onCloseReader = readerActions::closeFromNavigation,
        onNavigateWebDavBack = webDavViewModel::handleBack,
        onCloseWebDav = sourceActions::closeWebDav,
        onNavigateFileDirectoryBack = { fileDirectoryViewModel.handleBack() },
        onReturnToSources = {
            selectedTabName = AppTab.SOURCES.name
            localOpenError = null
            webDavActionMessage = null
        },
    )

    LaunchedEffect(selectedTab) {
        if (selectedTab == AppTab.SETTINGS) {
            cacheActions.refreshNow()
        }
    }

    ComicDavTheme(palette = appSettings.colorPalette) {
        Surface(modifier = Modifier.fillMaxSize()) {
            when {
                isDataFolderLoading -> {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                }

                dataFolderUriText.isNullOrBlank() -> {
                    DataFolderGateScreen(
                        onChooseFolder = activityLaunchers.chooseDataFolder,
                        modifier = Modifier.fillMaxSize(),
                    )
                }

                else -> {
                    ReaderOverlayHost(
                        readerOpenState = readerOpenState,
                        readerContent = {
                            ReaderRoute(
                                readerUiState = readerViewModel.uiState,
                                localOpenError = localOpenError,
                                downloadProgress = downloadProgress,
                                appSettings = appSettings,
                                readerLandscapeModeEnabled = readerLandscapeModeState.value,
                                readerLandscapeOrientationLocked = readerLandscapeOrientationLockedState.value,
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
                            selectedTab = selectedTab,
                            onTabSelected = { tab ->
                                selectedTabName = tab.name
                                localOpenError = null
                                webDavActionMessage = null
                                clearSelection()
                            },
                            bottomBar = selectionBottomBar(
                                selectedWebDavFile = selectedWebDavFile,
                                selectedDirectoryComic = selectedDirectoryComic,
                                selectedDirectoryVideo = selectedDirectoryVideo,
                                selectedLibraryItem = selectedLibraryItem,
                                selectedVideoLibraryItem = selectedVideoLibraryItem,
                                onDownloadWebDavFile = { item ->
                                    clearSelection()
                                    downloadActions.downloadWebDavComic(item)
                                },
                                onDownloadWebDavVideo = { item ->
                                    clearSelection()
                                    downloadActions.downloadWebDavVideo(item)
                                },
                                onAddWebDavFileToLibrary = { item ->
                                    clearSelection()
                                    comicActions.favoriteWebDavComic(item)
                                },
                                onAddWebDavVideoToVideoLibrary = { item ->
                                    clearSelection()
                                    videoActions.favoriteWebDavVideo(item)
                                },
                                onAddDirectoryComicToLibrary = { item ->
                                    clearSelection()
                                    comicActions.favoriteLocalDirectoryComic(item)
                                },
                                onAddDirectoryVideoToVideoLibrary = { item ->
                                    clearSelection()
                                    videoActions.favoriteLocalDirectoryVideo(item)
                                },
                                onRemoveLibraryItem = comicActions::removeLibraryItem,
                                onRefreshLibraryCover = comicActions::refreshLibraryCover,
                                onDownloadLibraryItem = { item ->
                                    appSelection = appSelection.clearIf { it is AppSelection.LibraryItem }
                                    downloadActions.downloadLibraryWebDavComic(item)
                                },
                                onRemoveVideoLibraryItem = videoActions::removeVideoLibraryItem,
                                onRefreshVideoLibraryThumbnail = videoActions::refreshVideoLibraryThumbnail,
                                onDeleteVideoLibraryThumbnail = videoActions::deleteVideoLibraryThumbnail,
                                onCancel = ::clearSelection,
                            ),
                        ) { contentModifier ->
                            when (selectedTab) {
                                AppTab.SOURCES -> AppSourcesRoute(
                                    state = AppSourcesRouteState(
                                        webDavUiState = uiState,
                                        fileDirectoryUiState = fileDirectoryUiState,
                                        isWebDavOpen = isWebDavOpen,
                                        isAddingWebDavPath = isAddingWebDavPath,
                                        editingWebDavSourceId = editingWebDavSourceId,
                                        localOpenError = localOpenError,
                                        actionMessage = webDavActionMessage,
                                        downloadProgress = downloadProgress,
                                        selection = appSelection,
                                    ),
                                    webDavViewModel = webDavViewModel,
                                    fileDirectoryViewModel = fileDirectoryViewModel,
                                    sourceActions = sourceActions,
                                    comicActions = comicActions,
                                    videoActions = videoActions,
                                    downloadActions = downloadActions,
                                    onChooseLocalDirectory = activityLaunchers.chooseLocalDirectory,
                                    onSelectionChange = { selection -> appSelection = selection },
                                    modifier = contentModifier,
                                )
                                AppTab.LIBRARY -> {
                                    LibraryTabContent(
                                        libraryUiState = libraryUiState,
                                        localOpenError = localOpenError,
                                        onOpenItem = comicActions::openLibraryItem,
                                        onSelectItem = { item ->
                                            appSelection = AppSelection.LibraryItem(item)
                                        },
                                        onOpenDirectories = {
                                            localOpenError = null
                                            selectedTabName = AppTab.SOURCES.name
                                        },
                                        onDismissMessage = {
                                            localOpenError = null
                                            libraryViewModel.clearMessage()
                                        },
                                        coversEnabled = appSettings.libraryCoversEnabled,
                                        selectedItemId = selectedLibraryItem?.item?.id,
                                        modifier = contentModifier,
                                    )
                                }
                                AppTab.VIDEO_LIBRARY -> {
                                    VideoLibraryTabContent(
                                        videoLibraryUiState = videoLibraryUiState,
                                        localOpenError = localOpenError,
                                        onOpenItem = videoActions::openVideoLibraryItem,
                                        onSelectItem = { item ->
                                            appSelection = AppSelection.VideoLibraryItem(item)
                                        },
                                        onOpenDirectories = {
                                            localOpenError = null
                                            selectedTabName = AppTab.SOURCES.name
                                        },
                                        onDismissMessage = {
                                            localOpenError = null
                                            videoLibraryViewModel.clearMessage()
                                        },
                                        thumbnailsEnabled = appSettings.videoLibraryThumbnailsEnabled,
                                        selectedItemId = selectedVideoLibraryItem?.item?.id,
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
                                            localOpenError = null
                                            webDavActionMessage = "暂无详情页面"
                                        },
                                        onOpenSources = {
                                            localOpenError = null
                                            selectedTabName = AppTab.SOURCES.name
                                        },
                                        actionMessage = localOpenError ?: webDavActionMessage,
                                        modifier = contentModifier,
                                    )
                                }
                                AppTab.SETTINGS -> {
                                    SettingsTabContent(
                                        settings = appSettings,
                                        appSettingsStore = appSettingsStore,
                                        scope = scope,
                                        cacheAnalysis = cacheAnalysis,
                                        cacheActionMessage = cacheActionMessage,
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
