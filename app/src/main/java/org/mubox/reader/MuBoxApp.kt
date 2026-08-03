package org.mubox.reader

import android.app.Activity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.core.view.WindowCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import org.mubox.reader.core.model.settings.AppSettings
import org.mubox.reader.core.model.settings.VideoSettings
import org.mubox.reader.core.model.history.WatchHistoryEntry
import org.mubox.reader.core.model.history.WatchMediaType
import org.mubox.reader.core.model.history.historyThumbnailStableKey
import org.mubox.reader.core.model.media.videoThumbnailStableKey
import org.mubox.reader.feature.home.HomeScreen
import org.mubox.reader.feature.downloads.DownloadsScreen
import org.mubox.reader.feature.downloads.activeProgress
import org.mubox.reader.feature.webdav.WEB_DAV_STATUS_CONNECTED
import org.mubox.reader.ui.MuBoxTheme
import java.io.File

@Composable
internal fun MuBoxApp(container: AppContainer) {
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
    val loadedAppSettings by appSettingsStore.settings.collectAsState(initial = null)
    val appSettings = loadedAppSettings ?: AppSettings(
        video = VideoSettings(videoResumeEnabled = false),
    )
    val downloadRecords by downloadRecordStore.records.collectAsState(initial = emptyList())
    val videoDownloadRecords by videoDownloadStore.records.collectAsState(initial = emptyList())
    val watchHistory by container.watchHistoryRepository.history.collectAsState(initial = emptyList())
    val retainedVideoThumbnailStableKeys = remember(watchHistory, videoLibraryUiState.items) {
        buildSet {
            videoLibraryUiState.items.mapNotNullTo(this, ::videoThumbnailStableKey)
            watchHistory
                .asSequence()
                .filter { entry -> entry.mediaType == WatchMediaType.VIDEO }
                .mapTo(this, ::historyThumbnailStableKey)
        }
    }
    val retainedVideoThumbnailFiles = remember(videoLibraryUiState.items) {
        videoLibraryUiState.items.mapNotNullTo(mutableSetOf()) { item ->
            item.item.thumbnailPath?.let(::File)?.absoluteFile
        }
    }
    LaunchedEffect(retainedVideoThumbnailStableKeys, retainedVideoThumbnailFiles) {
        container.videoThumbnailExtractor.updateRetainedThumbnails(
            stableKeys = retainedVideoThumbnailStableKeys,
            explicitFiles = retainedVideoThumbnailFiles,
        )
    }
    val historyMediaKeys = remember(watchHistory) {
        watchHistory.mapTo(linkedSetOf()) { entry -> entry.mediaKey }.toSet()
    }
    var observedHistoryMediaKeys by remember { mutableStateOf(historyMediaKeys) }
    var historyAdditionRevision by remember { mutableIntStateOf(0) }
    SideEffect {
        if (historyMediaKeys != observedHistoryMediaKeys) {
            if (hasHistoryEntryAdditions(observedHistoryMediaKeys, historyMediaKeys)) {
                historyAdditionRevision += 1
            }
            observedHistoryMediaKeys = historyMediaKeys
        }
    }
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
    var pendingHomeDeleteSelection by remember { mutableStateOf<HomeSelection?>(null) }
    val performHomeDelete: (HomeSelection) -> Unit = { selection ->
        watchHistory
            .filter { it.mediaKey in selection.historyKeys }
            .forEach(cacheActions::deleteHistoryEntry)
        libraryUiState.items
            .filter { it.item.id in selection.libraryItemIds }
            .forEach(comicActions::removeLibraryItem)
        videoLibraryUiState.items
            .filter { it.item.id in selection.videoLibraryItemIds }
            .forEach(videoActions::removeVideoLibraryItem)
    }
    LaunchedEffect(downloadState) {
        downloadActions.handleState(downloadState)
    }
    LaunchedEffect(context.cacheDir) {
        cacheActions.refreshNow()
    }
    LaunchedEffect(dataFolderStore) {
        ui.dataFolderUriText = dataFolderStore.loadFolderUri()
        ui.isDataFolderLoading = false
    }

    ReaderOrientationEffects(
        activity = context as? Activity,
        lifecycleOwner = lifecycleOwner,
        screenRotationLockEnabled = appSettings.appearance.screenRotationLockEnabled,
        readerOpenState = ui.readerOpenState,
        readerLandscapeModeState = ui.readerLandscapeModeState,
        readerLandscapeOrientationLockedState = ui.readerLandscapeOrientationLockedState,
        forceMainPortraitState = ui.forceMainPortraitState,
        configurationOrientation = configuration.orientation,
    )


    LaunchedEffect(appSettings.storage.pageImageCacheEnabled, appSettings.storage.diskCacheLimitMb) {
        cacheActions.applyReaderPageCacheSettings(
            pageImageCacheEnabled = appSettings.storage.pageImageCacheEnabled,
            diskCacheLimitMb = appSettings.storage.diskCacheLimitMb,
        )
    }

    // Prune only after the stored settings have loaded; the placeholder defaults above
    // (90 days / 200 records) must never drive deletion for users who chose looser limits.
    LaunchedEffect(
        loadedAppSettings?.history?.historyRetentionDays,
        loadedAppSettings?.history?.historyMaxRecords,
        historyAdditionRevision,
    ) {
        val historySettings = loadedAppSettings ?: return@LaunchedEffect
        cacheActions.pruneHistory(
            retentionDays = historySettings.history.historyRetentionDays,
            maxRecords = historySettings.history.historyMaxRecords,
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

    MuBoxBackHandler(
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
        onReturnToHome = ui::returnToHome,
    )

    LaunchedEffect(ui.selectedTab) {
        if (ui.selectedTab == AppTab.SETTINGS) {
            cacheActions.refreshNow()
        }
    }

    MuBoxTheme(palette = appSettings.appearance.colorPalette) {
        MuBoxSystemBarAppearance()
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background,
        ) {
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
                                onCancelLoading = readerActions::cancelLoading,
                                onClose = readerActions::close,
                                onAutoPageEnabledChange = readerActions::updateAutoPageEnabled,
                            )
                        },
                    ) {
                        val openHistoryEntry: (WatchHistoryEntry) -> Unit = { entry ->
                            when (entry.mediaType) {
                                WatchMediaType.COMIC -> comicActions.openHistoryEntry(entry)
                                WatchMediaType.VIDEO -> videoActions.openHistoryEntry(entry)
                            }
                        }
                        val dismissLibraryMessage: () -> Unit = {
                            ui.localOpenError = null
                            libraryViewModel.clearMessage()
                        }
                        val dismissVideoLibraryMessage: () -> Unit = {
                            videoLibraryViewModel.clearMessage()
                        }
                        val openSourcesTab: () -> Unit = {
                            ui.selectTab(AppTab.SOURCES)
                        }
                        MuBoxAppShell(
                            selectedTab = ui.selectedTab,
                            onTabSelected = ui::selectTab,
                            downloadsActive = downloadProgress != null,
                            bottomBar = selectionBottomBar(
                                homeSelection = ui.homeSelection,
                                selectedWebDavFile = ui.selectedWebDavFile,
                                selectedDirectoryComic = ui.selectedDirectoryComic,
                                selectedDirectoryVideo = ui.selectedDirectoryVideo,
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
                                onDeleteHomeSelection = {
                                    pendingHomeDeleteSelection = ui.homeSelection
                                },
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
                                    gridVideoThumbnailsEnabled = appSettings.video.gridVideoThumbnailsEnabled,
                                    modifier = contentModifier,
                                )
                                AppTab.HOME -> {
                                    HomeScreen(
                                        history = watchHistory,
                                        libraryItems = libraryUiState.items,
                                        videoLibraryItems = videoLibraryUiState.items,
                                        libraryMessage = libraryUiState.error
                                            ?: libraryUiState.message
                                            ?: ui.localOpenError,
                                        libraryMessageIsError =
                                            libraryUiState.error != null || ui.localOpenError != null,
                                        videoLibraryMessage = videoLibraryUiState.error
                                            ?: videoLibraryUiState.message,
                                        videoLibraryMessageIsError = videoLibraryUiState.error != null,
                                        coversEnabled = appSettings.appearance.libraryCoversEnabled,
                                        thumbnailsEnabled = appSettings.video.videoLibraryThumbnailsEnabled,
                                        isExtractingThumbnails =
                                            videoLibraryUiState.isExtractingThumbnails,
                                        videoThumbnailArtworkRevisions =
                                            videoLibraryUiState.thumbnailArtworkRevisions.videos,
                                        historyThumbnailArtworkRevisions =
                                            videoLibraryUiState.thumbnailArtworkRevisions.history,
                                        sharedVideoThumbnailArtworkRevision =
                                            videoLibraryUiState.thumbnailArtworkRevisions.sharedVideoArtwork,
                                        thumbnailExtractionMessage =
                                            videoLibraryUiState.thumbnailExtractionMessage,
                                        thumbnailExtractionMessageIsError =
                                            videoLibraryUiState.thumbnailExtractionMessageIsError,
                                        selectedHistoryKeys = ui.homeSelection.historyKeys,
                                        selectedLibraryItemIds = ui.homeSelection.libraryItemIds,
                                        selectedVideoLibraryItemIds = ui.homeSelection.videoLibraryItemIds,
                                        onOpenHistoryEntry = openHistoryEntry,
                                        onToggleHistorySelection = { entry ->
                                            ui.toggleHomeHistorySelection(entry.mediaKey)
                                        },
                                        onOpenLibraryItem = comicActions::openLibraryItem,
                                        onToggleLibrarySelection = { item ->
                                            ui.toggleHomeLibrarySelection(item.item.id)
                                        },
                                        onOpenVideoLibraryItem = videoActions::openVideoLibraryItem,
                                        onToggleVideoLibrarySelection = { item ->
                                            ui.toggleHomeVideoLibrarySelection(item.item.id)
                                        },
                                        onDismissLibraryMessage = dismissLibraryMessage,
                                        onDismissVideoLibraryMessage = dismissVideoLibraryMessage,
                                        onDismissThumbnailExtractionMessage =
                                            videoLibraryViewModel::clearThumbnailExtractionMessage,
                                        onExtractThumbnails = {
                                            videoActions.extractMissingThumbnails(
                                                videoLibraryItems = videoLibraryUiState.items,
                                                history = watchHistory,
                                                libraryItems = libraryUiState.items,
                                            )
                                        },
                                        onOpenSources = openSourcesTab,
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
                                        history = watchHistory,
                                        onOpenHistoryEntry = { entry ->
                                            when (entry.mediaType) {
                                                WatchMediaType.COMIC -> comicActions.openHistoryEntry(entry)
                                                WatchMediaType.VIDEO -> videoActions.openHistoryEntry(entry)
                                            }
                                        },
                                        onDeleteHistoryEntry = cacheActions::deleteHistoryEntry,
                                        onClearHistory = cacheActions::clearHistory,
                                        onClearCacheCategory = cacheActions::clearCategory,
                                        onClearAllCache = cacheActions::clearAll,
                                        modifier = contentModifier,
                                    )
                                }
                            }
                        }
                    }
                    pendingHomeDeleteSelection?.let { selection ->
                        HomeDeleteConfirmDialog(
                            selection = selection,
                            onConfirm = {
                                pendingHomeDeleteSelection = null
                                ui.clearSelection()
                                performHomeDelete(selection)
                            },
                            onDismiss = { pendingHomeDeleteSelection = null },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun HomeDeleteConfirmDialog(
    selection: HomeSelection,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val description = buildList {
        if (selection.historyKeys.isNotEmpty()) {
            add("${selection.historyKeys.size} 条观看记录（含关联缓存）")
        }
        if (selection.libraryItemIds.isNotEmpty()) {
            add("${selection.libraryItemIds.size} 部漫画")
        }
        if (selection.videoLibraryItemIds.isNotEmpty()) {
            add("${selection.videoLibraryItemIds.size} 部影视")
        }
    }.joinToString("、")
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("删除所选 ${selection.count} 项？") },
        text = { Text("将删除$description。此操作不可撤销。") },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text("删除", color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}

@Composable
private fun MuBoxSystemBarAppearance() {
    val view = LocalView.current
    val activity = view.context as? Activity
    val useLightIcons = MaterialTheme.colorScheme.background.luminance() < 0.5f
    SideEffect {
        activity?.window?.let { window ->
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = !useLightIcons
                isAppearanceLightNavigationBars = !useLightIcons
            }
        }
    }
}

internal fun hasHistoryEntryAdditions(
    previousMediaKeys: Set<String>,
    currentMediaKeys: Set<String>,
): Boolean = currentMediaKeys.any { mediaKey -> mediaKey !in previousMediaKeys }
