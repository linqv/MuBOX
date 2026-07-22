package com.example.comicdav

import android.app.Activity
import android.net.Uri
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
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.comicdav.data.AppSettings
import com.example.comicdav.data.ComicCacheAnalysis
import com.example.comicdav.data.ComicCacheCategory
import com.example.comicdav.data.DownloadRecord
import com.example.comicdav.data.ReaderLoggingMode
import com.example.comicdav.data.VideoDownloadRecord
import com.example.comicdav.data.filedirectory.FileDirectorySourceType
import com.example.comicdav.data.analyzeComicCache
import com.example.comicdav.data.clearComicCache
import com.example.comicdav.data.clearComicCacheCategory
import com.example.comicdav.data.formatCacheSize
import com.example.comicdav.feature.filedirectory.FileDirectoryBrowserItem
import com.example.comicdav.data.filedirectory.FileDirectorySourceEntity
import com.example.comicdav.data.library.LibraryItemWithSources
import com.example.comicdav.data.library.SourceType
import com.example.comicdav.feature.reader.ReaderDiagnosticLog
import com.example.comicdav.feature.reader.OpenComicUseCase
import com.example.comicdav.feature.reader.ReaderPageCache
import com.example.comicdav.feature.reader.localComicCacheKey
import com.example.comicdav.feature.reader.readerImageFormatCacheKey
import com.example.comicdav.feature.settings.pageCacheLimitBytesForSettings
import com.example.comicdav.feature.downloads.AndroidDownloadBackend
import com.example.comicdav.feature.downloads.ComicDownloadRequest
import com.example.comicdav.feature.downloads.DownloadCoordinator
import com.example.comicdav.feature.downloads.DownloadMediaType
import com.example.comicdav.feature.downloads.DownloadOrigin
import com.example.comicdav.feature.downloads.DownloadState
import com.example.comicdav.feature.downloads.DownloadsScreen
import com.example.comicdav.feature.downloads.VideoDownloadRequest
import com.example.comicdav.feature.downloads.activeProgress
import com.example.comicdav.feature.webdav.WEB_DAV_STATUS_CONNECTED
import com.example.comicdav.feature.webdav.WebDavAccountScreen
import com.example.comicdav.feature.webdav.WebDavBrowserScreen
import com.example.comicdav.network.RemoteFileInfo
import com.example.comicdav.network.WebDavItem
import com.example.comicdav.ui.ComicDavTheme
import com.example.comicdav.video.MediaKind
import com.example.comicdav.video.mediaKindFor
import com.example.comicdav.webdav.decodeWebDavPathForDisplay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun ComicDavApp(container: AppContainer) {
    val appViewModels = rememberAppViewModels(container)
    val webDavViewModel = appViewModels.webDav
    val readerViewModel = appViewModels.reader
    val libraryViewModel = appViewModels.library
    val videoLibraryViewModel = appViewModels.videoLibrary
    val fileDirectoryViewModel = appViewModels.fileDirectory
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    val libraryRepository = container.libraryRepository
    val localComicOpener = container.localComicOpener
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
    val remoteCache = container.remoteCache
    val coverExtractor = container.coverExtractor
    val progressStore = container.progressStore
    val dataFolderStore = container.dataFolderStore
    val appSettingsStore = container.appSettingsStore
    val webDavAccountStore = container.webDavAccountStore
    val downloadRecordStore = container.downloadRecordStore
    val videoDownloadStore = container.videoDownloadStore
    val downloadCoordinatorFactory = remember(context.applicationContext, downloadRecordStore, videoDownloadStore) {
        DownloadCoordinator.Factory(
            AndroidDownloadBackend(
                context = context.applicationContext,
                downloadRecordStore = downloadRecordStore,
                videoDownloadStore = videoDownloadStore,
            ),
        )
    }
    val downloadCoordinator: DownloadCoordinator = viewModel(factory = downloadCoordinatorFactory)
    val downloadState by downloadCoordinator.state.collectAsState()
    val downloadProgress = downloadState.activeProgress
    val appSettings by appSettingsStore.settings.collectAsState(initial = AppSettings(videoResumeEnabled = false))
    val downloadRecords by downloadRecordStore.records.collectAsState(initial = emptyList())
    val videoDownloadRecords by videoDownloadStore.records.collectAsState(initial = emptyList())
    fun clearSelection() {
        appSelection = appSelection.clear()
    }
    fun refreshCacheAnalysis() {
        scope.launch {
            cacheAnalysis = withContext(Dispatchers.IO) {
                analyzeComicCache(
                    cacheDir = context.cacheDir,
                    codeCacheDir = context.codeCacheDir,
                    externalCacheDirs = context.externalCacheDirs.filterNotNull(),
                )
            }
        }
    }
    fun cancelActiveDownload() {
        localOpenError = null
        webDavActionMessage = null
        downloadCoordinator.cancelActiveDownload()
    }
    LaunchedEffect(downloadState) {
        when (val result = downloadState) {
            is DownloadState.Succeeded -> {
                when (result.task.origin) {
                    DownloadOrigin.WEB_DAV_BROWSER -> {
                        localOpenError = null
                        webDavActionMessage = result.message
                        if (result.task.mediaType == DownloadMediaType.VIDEO) {
                            appSelection = appSelection.clearIf { it is AppSelection.WebDavFile }
                        }
                        fileDirectoryViewModel.showMessage(result.message)
                    }
                    DownloadOrigin.LIBRARY -> libraryViewModel.showMessage(result.message)
                }
                downloadCoordinator.acknowledgeTerminalState(result.task.id)
            }
            is DownloadState.Failed -> {
                when (result.task.origin) {
                    DownloadOrigin.WEB_DAV_BROWSER -> {
                        webDavActionMessage = null
                        localOpenError = result.message
                        fileDirectoryViewModel.showError(result.message)
                    }
                    DownloadOrigin.LIBRARY -> libraryViewModel.showError(result.message)
                }
                downloadCoordinator.acknowledgeTerminalState(result.task.id)
            }
            is DownloadState.Cancelled -> {
                localOpenError = null
                webDavActionMessage = result.message
                downloadCoordinator.acknowledgeTerminalState(result.task.id)
            }
            DownloadState.Idle,
            is DownloadState.Running,
            -> Unit
        }
    }
    LaunchedEffect(context.cacheDir) {
        cacheAnalysis = withContext(Dispatchers.IO) {
            analyzeComicCache(
                cacheDir = context.cacheDir,
                codeCacheDir = context.codeCacheDir,
                externalCacheDirs = context.externalCacheDirs.filterNotNull(),
            )
        }
    }
    LaunchedEffect(dataFolderStore) {
        dataFolderUriText = dataFolderStore.loadFolderUri()
        if (logFolderUriText.isNullOrBlank()) {
            logFolderUriText = dataFolderUriText
        }
        isDataFolderLoading = false
    }
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
        onLocalDirectorySelected = { displayName, treeUri ->
            fileDirectoryViewModel.addLocalDirectory(displayName = displayName, treeUri = treeUri)
        },
        onVideoPlayerClosed = { forceMainPortraitState.value = true },
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
        val pageCacheLimitBytes = pageCacheLimitBytesForSettings(
            pageImageCacheEnabled = appSettings.pageImageCacheEnabled,
            limitMb = appSettings.diskCacheLimitMb,
        )
        readerViewModel.updatePageImageCacheEnabled(appSettings.pageImageCacheEnabled)
        readerViewModel.updatePageCacheMaxBytes(pageCacheLimitBytes)
        withContext(Dispatchers.IO) {
            if (appSettings.pageImageCacheEnabled) {
                ReaderPageCache.prune(context.cacheDir, maxBytes = pageCacheLimitBytes)
            } else {
                clearComicCacheCategory(context.cacheDir, ComicCacheCategory.READER_PAGES)
            }
        }
        refreshCacheAnalysis()
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

    fun openDirectLocalComic(
        uri: Uri,
        fileName: String,
        comicKey: String,
        readyEvent: String,
        failureEvent: String,
        onOpened: () -> Unit = {},
        onFailure: (Throwable) -> Unit,
    ) {
        startReaderLogFile(context, logFolderUriText, scope, appSettings.readerLoggingMode != ReaderLoggingMode.OFF)
        val avifImagesEnabled = effectiveAvifImagesEnabled(appSettings.avifImagesEnabled)
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    localComicOpener.open(uri, fileName, avifImagesEnabled = avifImagesEnabled)
                }
            }.fold(
                onSuccess = { session ->
                    localOpenError = null
                    onOpened()
                    ReaderDiagnosticLog.event("$readyEvent fileName=$fileName")
                    readerViewModel.openExistingSession(
                        openedSession = session,
                        cacheDir = context.cacheDir,
                        initialPage = 0,
                        comicKey = comicKey,
                        pageCacheKey = readerImageFormatCacheKey(comicKey, avifImagesEnabled),
                    )
                    readerOpenState.value = true
                },
                onFailure = { error ->
                    ReaderDiagnosticLog.error(failureEvent, error)
                    onFailure(error)
                },
            )
        }
    }

    fun openLocalLibraryComic(item: LibraryItemWithSources) {
        val source = item.localSource ?: run {
            localOpenError = "缺少本地来源"
            return
        }
        openDirectLocalComic(
            uri = Uri.parse(source.uri),
            fileName = source.fileName,
            comicKey = localComicCacheKey(
                prefix = "library",
                stableId = "${item.item.id}:${source.uri}",
                size = source.size,
                lastModified = source.lastModified,
            ),
            readyEvent = "open_library_local_fd_ready",
            failureEvent = "open_library_local_fd_failed",
            onOpened = { libraryViewModel.markOpened(item.item.id) },
            onFailure = { error -> localOpenError = error.message ?: "打开本地文件失败" },
        )
    }

    fun openLocalDirectoryComic(item: FileDirectoryBrowserItem) {
        openDirectLocalComic(
            uri = Uri.parse(item.uri),
            fileName = item.name,
            comicKey = localComicCacheKey(
                prefix = "directory",
                stableId = item.uri,
                size = item.size,
                lastModified = item.lastModified,
            ),
            readyEvent = "open_directory_local_fd_ready",
            failureEvent = "open_directory_local_fd_failed uri=${item.uri}",
            onFailure = { error -> fileDirectoryViewModel.showError(error.message ?: "打开本地文件失败") },
        )
    }

    fun favoriteLocalDirectoryComic(item: FileDirectoryBrowserItem) {
        scope.launch {
            runCatching {
                libraryRepository.addLocalComic(
                    uri = item.uri,
                    fileName = item.name,
                    size = item.size,
                    lastModified = item.lastModified,
                )
            }.fold(
                onSuccess = {
                    fileDirectoryViewModel.showMessage("已将 ${item.name} 加入书架")
                },
                onFailure = { error ->
                    ReaderDiagnosticLog.error("favorite_local_directory_comic_failed uri=${item.uri}", error)
                    fileDirectoryViewModel.showError(error.message ?: "加入书架失败")
                },
            )
        }
    }

    fun favoriteWebDavComic(item: WebDavItem) {
        val client = webDavViewModel.activeClient()
        val accountId = webDavViewModel.activeAccountId() ?: webDavViewModel.accountId()
        if (client == null) {
            localOpenError = "请先连接 WebDAV，再加入书架"
            webDavActionMessage = null
            return
        }
        localOpenError = null
        webDavActionMessage = null
        scope.launch {
            runCatching {
                val knownInfo = item.size?.let { knownSize ->
                    RemoteFileInfo(
                        path = item.path,
                        size = knownSize,
                        etag = item.etag,
                        lastModified = item.lastModified,
                        supportsRange = true,
                    )
                }
                val coverPath = if (appSettings.libraryCoversEnabled) {
                    runCatching {
                        coverExtractor.extractFirstPageCover(
                            client = client,
                            accountId = accountId,
                            remotePath = item.path,
                            avifImagesEnabled = effectiveAvifImagesEnabled(appSettings.avifImagesEnabled),
                            knownInfo = knownInfo,
                        )
                    }.onFailure { error ->
                        ReaderDiagnosticLog.error("extract_webdav_cover_failed path=${item.path}", error)
                    }.getOrNull()
                } else {
                    null
                }
                libraryRepository.addWebDavComic(
                    accountId = accountId,
                    remotePath = item.path,
                    fileName = item.name,
                    size = item.size,
                    etag = item.etag,
                    lastModified = item.lastModified,
                    coverPath = coverPath,
                )
            }.fold(
                onSuccess = {
                    refreshCacheAnalysis()
                    webDavActionMessage = "已将 ${item.name} 加入书架"
                    libraryViewModel.showMessage("已将 ${item.name} 加入书架")
                    fileDirectoryViewModel.showMessage("已将 ${item.name} 加入书架")
                },
                onFailure = { error ->
                    localOpenError = error.message ?: "添加 WebDAV 漫画失败"
                    ReaderDiagnosticLog.error("add_webdav_library_failed path=${item.path}", error)
                    libraryViewModel.showError(error.message ?: "添加 WebDAV 漫画失败")
                    fileDirectoryViewModel.showError(error.message ?: "添加 WebDAV 漫画失败")
                },
            )
        }
    }

    fun downloadWebDavComicToLocal(item: WebDavItem) {
        val client = webDavViewModel.activeClient()
        val accountId = webDavViewModel.activeAccountId() ?: webDavViewModel.accountId()
        val folderUriText = dataFolderUriText
        if (client == null) {
            localOpenError = "请先连接 WebDAV，再下载漫画"
            webDavActionMessage = null
            return
        }
        if (folderUriText.isNullOrBlank()) {
            localOpenError = "请先选择 MuBOX 数据文件夹，再下载漫画"
            webDavActionMessage = null
            return
        }
        localOpenError = null
        webDavActionMessage = null
        downloadCoordinator.downloadComic(
            request = ComicDownloadRequest(
                folderUri = folderUriText,
                accountId = accountId,
                remotePath = item.path,
                fileName = item.name,
                size = item.size,
                etag = item.etag,
                lastModified = item.lastModified,
                origin = DownloadOrigin.WEB_DAV_BROWSER,
            ),
        ) {
            client
        }
    }

    fun downloadWebDavVideoToLocal(item: WebDavItem) {
        val client = webDavViewModel.activeClient()
        val accountId = webDavViewModel.activeAccountId() ?: webDavViewModel.accountId()
        val folderUriText = dataFolderUriText
        if (client == null) {
            localOpenError = "请先连接 WebDAV，再下载视频"
            webDavActionMessage = null
            return
        }
        if (folderUriText.isNullOrBlank()) {
            localOpenError = "请先选择 MuBOX 数据文件夹，再下载视频"
            webDavActionMessage = null
            return
        }
        localOpenError = null
        webDavActionMessage = null
        downloadCoordinator.downloadVideo(
            request = VideoDownloadRequest(
                folderUri = folderUriText,
                accountId = accountId,
                remotePath = item.path,
                fileName = item.name,
                size = item.size,
                etag = item.etag,
                lastModified = item.lastModified,
            ),
            client = client,
        )
    }

    fun downloadRemoteComicToLocal(
        accountId: String,
        remotePath: String,
        fileName: String,
        size: Long?,
        etag: String?,
        lastModified: Long?,
    ) {
        val folderUriText = dataFolderUriText
        if (folderUriText.isNullOrBlank()) {
            libraryViewModel.showError("请先选择 MuBOX 数据文件夹，再下载漫画")
            return
        }
        localOpenError = null
        webDavActionMessage = null
        downloadCoordinator.downloadComic(
            request = ComicDownloadRequest(
                folderUri = folderUriText,
                accountId = accountId,
                remotePath = remotePath,
                fileName = fileName,
                size = size,
                etag = etag,
                lastModified = lastModified,
                origin = DownloadOrigin.LIBRARY,
            ),
        ) {
            container.webDavClientProvider.clientFor(accountId)
        }
    }

    fun downloadLibraryWebDavComic(item: LibraryItemWithSources) {
        val source = item.webDavSource ?: run {
            libraryViewModel.showError("本地漫画无需下载")
            return
        }
        downloadRemoteComicToLocal(
            accountId = source.accountId,
            remotePath = source.remotePath,
            fileName = source.fileName,
            size = source.size,
            etag = source.etag,
            lastModified = source.lastModified,
        )
    }

    fun removeLibraryItem(item: LibraryItemWithSources) {
        scope.launch {
            runCatching {
                libraryRepository.removeComic(item.item.id)
            }.fold(
                onSuccess = {
                    appSelection = appSelection.clearIf { it is AppSelection.LibraryItem }
                    libraryViewModel.showMessage("已将 ${item.item.displayName} 移出书架")
                },
                onFailure = { error ->
                    libraryViewModel.showError(error.message ?: "移出书架失败")
                },
            )
        }
    }

    fun refreshLibraryCover(item: LibraryItemWithSources) {
        val source = item.webDavSource ?: run {
            libraryViewModel.showError("本地漫画暂不支持重新获取封面")
            return
        }
        scope.launch {
            runCatching {
                val client = webDavResolver.clientFor(source.accountId) ?: error("请先连接 ${source.accountId}，再重新获取封面")
                coverExtractor.extractFirstPageCover(
                    client = client,
                    accountId = source.accountId,
                    remotePath = source.remotePath,
                    avifImagesEnabled = effectiveAvifImagesEnabled(appSettings.avifImagesEnabled),
                    knownInfo = source.size?.let { knownSize ->
                        RemoteFileInfo(
                            path = source.remotePath,
                            size = knownSize,
                            etag = source.etag,
                            lastModified = source.lastModified,
                            supportsRange = true,
                        )
                    },
                )
            }.fold(
                onSuccess = { coverPath ->
                    libraryRepository.updateCoverPath(item.item.id, coverPath)
                    appSelection = appSelection.clearIf { it is AppSelection.LibraryItem }
                    refreshCacheAnalysis()
                    libraryViewModel.showMessage("已重新获取 ${item.item.displayName} 的封面")
                },
                onFailure = { error ->
                    ReaderDiagnosticLog.error("refresh_library_cover_failed id=${item.item.id}", error)
                    libraryViewModel.showError(error.message ?: "重新获取封面失败")
                },
            )
        }
    }

    fun removeComicDownloadRecord(record: DownloadRecord) {
        scope.launch {
            downloadRecordStore.removeRecord(record)
        }
    }

    fun deleteComicDownloadFile(record: DownloadRecord) {
        scope.launch {
            val uriText = downloadLocalUriTextOrNull(record.localUri)
            if (uriText == null) {
                downloadRecordStore.removeRecord(record)
                localOpenError = null
                webDavActionMessage = "已从列表移除 ${record.fileName}（缺少本地文件位置）"
                return@launch
            }
            val uri = Uri.parse(uriText)
            val shouldRemoveRecord = deleteDownloadDocumentAndShouldRemoveRecord(
                context = context,
                uri = uri,
                diagnosticName = "delete_comic_download_file",
            )
            if (shouldRemoveRecord) {
                downloadRecordStore.removeRecord(record)
                localOpenError = null
                webDavActionMessage = "已删除 ${record.fileName}"
            } else {
                localOpenError = "无法删除 ${record.fileName}，下载记录已保留"
            }
        }
    }

    fun deleteVideoDownloadRecord(record: VideoDownloadRecord) {
        scope.launch {
            val uri = Uri.parse(record.localUri)
            val shouldRemoveRecord = deleteDownloadDocumentAndShouldRemoveRecord(
                context = context,
                uri = uri,
                diagnosticName = "delete_video_download_file",
            )
            if (shouldRemoveRecord) {
                videoDownloadStore.removeRecord(record)
                localOpenError = null
                webDavActionMessage = "已删除 ${record.fileName}"
            } else {
                localOpenError = "无法删除 ${record.fileName}，下载记录已保留"
            }
        }
    }

    fun removeVideoDownloadRecord(record: VideoDownloadRecord) {
        scope.launch {
            videoDownloadStore.removeRecord(record)
            webDavActionMessage = "已从列表移除 ${record.fileName}"
        }
    }

    fun closeReaderFromNavigation() {
        ReaderDiagnosticLog.event("reader_navigation_close")
        val shouldRestoreMainPortrait = readerLandscapeModeState.value
        readerViewModel.closeReader()
        webDavActionMessage = null
        readerLandscapeModeState.value = readerLandscapeModeAfterReaderClosed()
        readerLandscapeOrientationLockedState.value = false
        readerOpenState.value = false
        if (shouldRestoreMainPortrait) {
            forceMainPortraitState.value = true
        }
    }

    fun deleteLocalSourceWithFiles(source: FileDirectorySourceEntity) {
        val treeUriText = source.localTreeUri
        if (treeUriText.isNullOrBlank()) {
            fileDirectoryViewModel.deleteSource(source.id)
            return
        }
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    deleteLocalSourceTree(context, Uri.parse(treeUriText))
                }
            }.fold(
                onSuccess = {
                    fileDirectoryViewModel.deleteSource(source.id)
                    fileDirectoryViewModel.showMessage("已删除来源和源文件")
                },
                onFailure = { error ->
                    ReaderDiagnosticLog.error("delete_local_source_files_failed uri=$treeUriText", error)
                    fileDirectoryViewModel.showError(error.message ?: "删除源文件失败")
                },
            )
        }
    }

    fun openRemoteComic(
        accountId: String,
        remotePath: String,
        size: Long?,
        etag: String?,
        lastModified: Long?,
        onOpenSucceeded: (() -> Unit)? = null,
    ) {
        val client = webDavViewModel.activeClient()
        val activeAccountId = webDavViewModel.activeAccountId()
        if (client == null || activeAccountId != accountId) {
            scope.launch {
                val savedAccount = webDavAccountStore.loadAccount(accountId)
                if (savedAccount == null) {
                    localOpenError = "请先连接 $accountId，再打开这个 WebDAV 漫画"
                    isWebDavOpen = true
                    return@launch
                }
                localOpenError = null
                webDavViewModel.connectToSavedSource(
                    baseUrl = savedAccount.baseUrl,
                    username = savedAccount.username,
                    password = savedAccount.password,
                    path = "/",
                )
                openRemoteComic(
                    accountId = accountId,
                    remotePath = remotePath,
                    size = size,
                    etag = etag,
                    lastModified = lastModified,
                    onOpenSucceeded = onOpenSucceeded,
                )
            }
            return
        }
        localOpenError = null
        webDavActionMessage = null
        readerOpenState.value = true
        startReaderLogFile(context, logFolderUriText, scope, appSettings.readerLoggingMode != ReaderLoggingMode.OFF)
        ReaderDiagnosticLog.event("open_remote_start path=$remotePath size=${size ?: -1}")
        val avifImagesEnabled = effectiveAvifImagesEnabled(appSettings.avifImagesEnabled)
        readerViewModel.openRemote(cacheDir = context.cacheDir) {
            val useCase = OpenComicUseCase(
                accountId = accountId,
                cache = remoteCache,
                progressStore = progressStore,
                avifImagesEnabled = avifImagesEnabled,
                webDavPrefetchPageCount = appSettings.webDavPrefetchPageCount,
            )
            val result = useCase.open(
                client = client,
                remotePath = remotePath,
                knownInfo = size?.let { knownSize ->
                    RemoteFileInfo(
                        path = remotePath,
                        size = knownSize,
                        etag = etag,
                        lastModified = lastModified,
                        supportsRange = true,
                    )
                },
            )
            onOpenSucceeded?.invoke()
            result
        }
    }

    fun openDownloadRecordComic(record: DownloadRecord) {
        val localUri = record.localUri
        if (!localUri.isNullOrBlank()) {
            openDirectLocalComic(
                Uri.parse(localUri),
                record.fileName,
                localComicCacheKey("download", localUri, record.sizeBytes, record.downloadedAtMillis),
                "open_download_local_ready",
                "open_download_local_failed uri=$localUri",
                onFailure = { error ->
                    localOpenError = error.message ?: "无法打开这条下载记录，文件可能已被删除"
                },
            )
            return
        }

        val accountId = record.accountId
            ?: webDavViewModel.activeAccountId()
            ?: webDavViewModel.accountId().takeIf { it.substringBefore("|").isNotBlank() }
        if (accountId.isNullOrBlank()) {
            localOpenError = "这条下载记录缺少 WebDAV 账号，也没有本地文件位置"
            return
        }
        openRemoteComic(accountId, record.remotePath, record.sizeBytes, null, null)
    }

    fun startAddingWebDavSource() {
        localOpenError = null
        webDavActionMessage = null
        editingWebDavSourceId = null
        webDavViewModel.startNewConnection()
        isWebDavOpen = true
        isAddingWebDavPath = true
    }

    fun openLibraryTabFromSources() {
        localOpenError = null
        webDavActionMessage = null
        selectedTabName = AppTab.LIBRARY.name
    }

    fun openFileDirectorySource(source: FileDirectorySourceEntity) {
        when (source.sourceType) {
            FileDirectorySourceType.LOCAL -> {
                webDavActionMessage = null
                fileDirectoryViewModel.openLocalSource(source)
            }
            FileDirectorySourceType.WEBDAV -> {
                val expectedAccountId = source.webDavAccountId
                val path = source.webDavPath ?: "/"
                webDavActionMessage = null
                isWebDavOpen = true
                isAddingWebDavPath = false
                editingWebDavSourceId = null
                scope.launch {
                    if (expectedAccountId != null && webDavViewModel.activeAccountId() == expectedAccountId) {
                        localOpenError = null
                        webDavActionMessage = null
                        webDavViewModel.openPath(path)
                        return@launch
                    }
                    val savedAccount = expectedAccountId?.let { accountId ->
                        webDavAccountStore.loadAccount(accountId)
                    }
                    val baseUrl = source.webDavBaseUrl
                        ?.takeIf { it.isNotBlank() }
                        ?: savedAccount?.baseUrl
                    if (baseUrl.isNullOrBlank()) {
                        localOpenError = "请先连接 ${expectedAccountId.orEmpty()}，再打开这个 WebDAV 目录"
                        webDavActionMessage = null
                        return@launch
                    }
                    localOpenError = null
                    webDavActionMessage = null
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
                }
            }
        }
    }

    fun selectDirectoryComicItem(item: FileDirectoryBrowserItem) {
        appSelection = AppSelection.DirectoryComic(item)
    }

    fun selectDirectoryVideoItem(item: FileDirectoryBrowserItem) {
        appSelection = AppSelection.DirectoryVideo(item)
    }

    fun dismissFileDirectoryMessage() {
        localOpenError = null
        fileDirectoryViewModel.clearMessage()
    }

    fun editWebDavSource(source: FileDirectorySourceEntity) {
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
        editingWebDavSourceId = source.id
        isAddingWebDavPath = false
        isWebDavOpen = true
        localOpenError = null
        webDavActionMessage = null
    }

    val hasActiveSelection = appSelection.isActive

    ComicDavBackHandler(
        hasActiveSelection = hasActiveSelection,
        readerOpenState = readerOpenState,
        isWebDavOpen = isWebDavOpen,
        hasOpenFileDirectory = fileDirectoryUiState.currentTitle != null,
        selectedTab = selectedTab,
        onClearSelection = ::clearSelection,
        onCloseReader = ::closeReaderFromNavigation,
        onNavigateWebDavBack = webDavViewModel::handleBack,
        onCloseWebDav = {
            isWebDavOpen = false
            isAddingWebDavPath = false
            editingWebDavSourceId = null
            localOpenError = null
            webDavActionMessage = null
        },
        onNavigateFileDirectoryBack = { fileDirectoryViewModel.handleBack() },
        onReturnToSources = {
            selectedTabName = AppTab.SOURCES.name
            localOpenError = null
            webDavActionMessage = null
        },
    )

    LaunchedEffect(selectedTab) {
        if (selectedTab == AppTab.SETTINGS) {
            cacheAnalysis = withContext(Dispatchers.IO) {
                analyzeComicCache(
                    cacheDir = context.cacheDir,
                    codeCacheDir = context.codeCacheDir,
                    externalCacheDirs = context.externalCacheDirs.filterNotNull(),
                )
            }
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
                                onReaderLandscapeModeChange = { value ->
                                    if (
                                        shouldForcePortraitAfterReaderLandscapeModeChange(
                                            currentReaderLandscapeModeEnabled = readerLandscapeModeState.value,
                                            nextReaderLandscapeModeEnabled = value,
                                        )
                                    ) {
                                        forceMainPortraitState.value = true
                                    } else if (value) {
                                        forceMainPortraitState.value = false
                                    }
                                    readerLandscapeModeState.value = value
                                    if (!value) {
                                        readerLandscapeOrientationLockedState.value = false
                                    }
                                },
                                onReaderLandscapeOrientationLockedChange = { value ->
                                    readerLandscapeOrientationLockedState.value = value
                                },
                                onPageChanged = readerViewModel::selectPage,
                                onPageDemanded = readerViewModel::reportPageDemand,
                                onImageLoadStarted = readerViewModel::reportImageLoadStarted,
                                onImageLoadSucceeded = readerViewModel::reportImageLoadSucceeded,
                                onImageLoadFailed = readerViewModel::reportImageLoadFailed,
                                onChooseLogFile = {
                                    if (appSettings.loggingEnabled) {
                                        activityLaunchers.chooseLogFolder()
                                    }
                                },
                                onCancelLoading = {
                                    ReaderDiagnosticLog.event("reader_open_cancel")
                                    val shouldRestoreMainPortrait = readerLandscapeModeState.value
                                    readerViewModel.closeReader()
                                    readerLandscapeModeState.value = readerLandscapeModeAfterReaderClosed()
                                    readerLandscapeOrientationLockedState.value = false
                                    readerOpenState.value = false
                                    if (shouldRestoreMainPortrait) {
                                        forceMainPortraitState.value = true
                                    }
                                },
                                onClose = {
                                    ReaderDiagnosticLog.event("reader_close")
                                    val shouldRestoreMainPortrait = readerLandscapeModeState.value
                                    readerViewModel.closeReader()
                                    readerLandscapeModeState.value = readerLandscapeModeAfterReaderClosed()
                                    readerLandscapeOrientationLockedState.value = false
                                    readerOpenState.value = false
                                    if (shouldRestoreMainPortrait) {
                                        forceMainPortraitState.value = true
                                    }
                                },
                                onAutoPageEnabledChange = { value ->
                                    scope.launch { appSettingsStore.updateAutoPageEnabled(value) }
                                },
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
                                    downloadWebDavComicToLocal(item)
                                },
                                onDownloadWebDavVideo = { item ->
                                    clearSelection()
                                    downloadWebDavVideoToLocal(item)
                                },
                                onAddWebDavFileToLibrary = { item ->
                                    clearSelection()
                                    favoriteWebDavComic(item)
                                },
                                onAddWebDavVideoToVideoLibrary = { item ->
                                    clearSelection()
                                    videoActions.favoriteWebDavVideo(item)
                                },
                                onAddDirectoryComicToLibrary = { item ->
                                    clearSelection()
                                    favoriteLocalDirectoryComic(item)
                                },
                                onAddDirectoryVideoToVideoLibrary = { item ->
                                    clearSelection()
                                    videoActions.favoriteLocalDirectoryVideo(item)
                                },
                                onRemoveLibraryItem = ::removeLibraryItem,
                                onRefreshLibraryCover = ::refreshLibraryCover,
                                onDownloadLibraryItem = { item ->
                                    appSelection = appSelection.clearIf { it is AppSelection.LibraryItem }
                                    downloadLibraryWebDavComic(item)
                                },
                                onRemoveVideoLibraryItem = videoActions::removeVideoLibraryItem,
                                onRefreshVideoLibraryThumbnail = videoActions::refreshVideoLibraryThumbnail,
                                onDeleteVideoLibraryThumbnail = videoActions::deleteVideoLibraryThumbnail,
                                onCancel = ::clearSelection,
                            ),
                        ) { contentModifier ->
                            when (selectedTab) {
                                AppTab.SOURCES -> {
                                    if (isWebDavOpen) {
                                        if (uiState.status == WEB_DAV_STATUS_CONNECTED) {
                                            WebDavBrowserScreen(
                                                uiState = uiState,
                                                onItemClick = { item ->
                                                    when (mediaKindFor(name = item.name, isDirectory = item.isDirectory)) {
                                                        MediaKind.Directory -> webDavViewModel.openDirectory(item)
                                                        MediaKind.Comic -> openRemoteComic(
                                                            accountId = webDavViewModel.activeAccountId() ?: webDavViewModel.accountId(),
                                                            remotePath = item.path,
                                                            size = item.size,
                                                            etag = item.etag,
                                                            lastModified = item.lastModified,
                                                        )
                                                        MediaKind.Video -> videoActions.openWebDavVideo(item)
                                                        MediaKind.Audio,
                                                        MediaKind.Subtitle,
                                                        MediaKind.Unknown,
                                                        -> Unit
                                                    }
                                                },
                                                onAddToLibrary = ::favoriteWebDavComic,
                                                onDownloadToLocal = ::downloadWebDavComicToLocal,
                                                onSelectFile = { item ->
                                                    appSelection = AppSelection.WebDavFile(item)
                                                },
                                                onSaveDirectory = {
                                                    val accountId = webDavViewModel.activeAccountId() ?: webDavViewModel.accountId()
                                                    fileDirectoryViewModel.addWebDavDirectory(
                                                        displayName = decodeWebDavPathForDisplay(uiState.currentPath),
                                                        accountId = accountId,
                                                        path = uiState.currentPath,
                                                        baseUrl = uiState.baseUrl,
                                                        username = uiState.username,
                                                        password = uiState.password,
                                                    )
                                                    isAddingWebDavPath = false
                                                },
                                                showSaveDirectoryAction = isAddingWebDavPath,
                                                downloadProgress = downloadProgress,
                                                downloadError = localOpenError,
                                                actionMessage = webDavActionMessage,
                                                onCancelDownload = ::cancelActiveDownload,
                                                onSearchQueryChange = webDavViewModel::updateSearchQuery,
                                                onSortFieldChange = webDavViewModel::updateSortField,
                                                onToggleSortDirection = webDavViewModel::toggleSortDirection,
                                                onRefresh = webDavViewModel::refreshCurrentDirectory,
                                                selectedFile = selectedWebDavFile,
                                                modifier = contentModifier,
                                            )
                                        } else if (
                                            shouldShowWebDavAccountForm(
                                                isAddingWebDavPath = isAddingWebDavPath,
                                                editingWebDavSourceId = editingWebDavSourceId,
                                                webDavStatus = uiState.status,
                                            )
                                        ) {
                                            WebDavAccountScreen(
                                                uiState = uiState,
                                                onDisplayNameChange = webDavViewModel::updateDisplayName,
                                                onHostChange = webDavViewModel::updateHost,
                                                onPortChange = webDavViewModel::updatePort,
                                                onRootPathChange = webDavViewModel::updateRootPath,
                                                onUseHttpsChange = webDavViewModel::updateUseHttps,
                                                onAnonymousAccessChange = webDavViewModel::updateAnonymousAccess,
                                                onUsernameChange = webDavViewModel::updateUsername,
                                                onPasswordChange = webDavViewModel::updatePassword,
                                                onTestConnection = {
                                                    val latestWebDavState = webDavViewModel.uiState
                                                    val username = if (latestWebDavState.anonymousAccess) "" else latestWebDavState.username
                                                    val password = if (latestWebDavState.anonymousAccess) "" else latestWebDavState.password
                                                    val displayName = latestWebDavState.displayName
                                                        .takeIf { it.isNotBlank() }
                                                        ?: latestWebDavState.host.takeIf { it.isNotBlank() }
                                                        ?: latestWebDavState.baseUrl
                                                    val accountId = "${latestWebDavState.baseUrl.trim()}|$username"
                                                    val sourceId = editingWebDavSourceId
                                                    if (sourceId != null) {
                                                        fileDirectoryViewModel.updateWebDavDirectory(
                                                            id = sourceId,
                                                            displayName = displayName,
                                                            accountId = accountId,
                                                            path = latestWebDavState.currentPath,
                                                            baseUrl = latestWebDavState.baseUrl,
                                                            username = username,
                                                            password = password,
                                                        )
                                                    } else {
                                                        fileDirectoryViewModel.addWebDavDirectory(
                                                            displayName = displayName,
                                                            accountId = accountId,
                                                            path = "/",
                                                            baseUrl = latestWebDavState.baseUrl,
                                                            username = username,
                                                            password = password,
                                                        )
                                                    }
                                                    isWebDavOpen = false
                                                    isAddingWebDavPath = false
                                                    editingWebDavSourceId = null
                                                },
                                                onBackToLibrary = {
                                                    isWebDavOpen = false
                                                    isAddingWebDavPath = false
                                                    editingWebDavSourceId = null
                                                    localOpenError = null
                                                    webDavActionMessage = null
                                                },
                                                message = localOpenError,
                                                modifier = contentModifier,
                                            )
                                        } else {
                                            FileDirectoryTabContent(
                                                fileDirectoryUiState = fileDirectoryUiState,
                                                localOpenError = localOpenError,
                                                webDavMessage = uiState.message.takeIf { it.isNotBlank() },
                                                selectedDirectoryComic = selectedDirectoryComic,
                                                selectedDirectoryVideo = selectedDirectoryVideo,
                                                onAddLocalDirectory = activityLaunchers.chooseLocalDirectory,
                                                onOpenWebDav = ::startAddingWebDavSource,
                                                onOpenLibrary = ::openLibraryTabFromSources,
                                                onOpenSource = ::openFileDirectorySource,
                                                onOpenDirectory = fileDirectoryViewModel::openLocalDirectory,
                                                onOpenComic = ::openLocalDirectoryComic,
                                                onOpenVideo = videoActions::openLocalDirectoryVideo,
                                                onSelectComic = ::selectDirectoryComicItem,
                                                onSelectVideo = ::selectDirectoryVideoItem,
                                                onDismissMessage = ::dismissFileDirectoryMessage,
                                                onSearchQueryChange = fileDirectoryViewModel::updateSearchQuery,
                                                onSortFieldChange = fileDirectoryViewModel::updateSortField,
                                                onToggleSortDirection = fileDirectoryViewModel::toggleSortDirection,
                                                onRefresh = fileDirectoryViewModel::refreshCurrentDirectory,
                                                onDeleteSource = { source -> fileDirectoryViewModel.deleteSource(source.id) },
                                                onDeleteLocalSourceWithFiles = ::deleteLocalSourceWithFiles,
                                                onEditWebDavSource = ::editWebDavSource,
                                                modifier = contentModifier,
                                            )
                                        }
                                    } else {
                                        FileDirectoryTabContent(
                                            fileDirectoryUiState = fileDirectoryUiState,
                                            localOpenError = localOpenError,
                                            webDavMessage = null,
                                            selectedDirectoryComic = selectedDirectoryComic,
                                            selectedDirectoryVideo = selectedDirectoryVideo,
                                            onAddLocalDirectory = activityLaunchers.chooseLocalDirectory,
                                            onOpenWebDav = ::startAddingWebDavSource,
                                            onOpenLibrary = ::openLibraryTabFromSources,
                                            onOpenSource = ::openFileDirectorySource,
                                            onOpenDirectory = fileDirectoryViewModel::openLocalDirectory,
                                            onOpenComic = ::openLocalDirectoryComic,
                                            onOpenVideo = videoActions::openLocalDirectoryVideo,
                                            onSelectComic = ::selectDirectoryComicItem,
                                            onSelectVideo = ::selectDirectoryVideoItem,
                                            onDismissMessage = ::dismissFileDirectoryMessage,
                                            onSearchQueryChange = fileDirectoryViewModel::updateSearchQuery,
                                            onSortFieldChange = fileDirectoryViewModel::updateSortField,
                                            onToggleSortDirection = fileDirectoryViewModel::toggleSortDirection,
                                            onRefresh = fileDirectoryViewModel::refreshCurrentDirectory,
                                            onDeleteSource = { source -> fileDirectoryViewModel.deleteSource(source.id) },
                                            onDeleteLocalSourceWithFiles = ::deleteLocalSourceWithFiles,
                                            onEditWebDavSource = ::editWebDavSource,
                                            modifier = contentModifier,
                                        )
                                    }
                                }
                                AppTab.LIBRARY -> {
                                    LibraryTabContent(
                                        libraryUiState = libraryUiState,
                                        localOpenError = localOpenError,
                                        onOpenItem = { item ->
                                            when (item.item.sourceType) {
                                                SourceType.LOCAL -> openLocalLibraryComic(item)
                                                SourceType.WEBDAV -> {
                                                    val source = item.webDavSource
                                                    if (source == null) {
                                                        localOpenError = "缺少 WebDAV 来源"
                                                    } else {
                                                        openRemoteComic(
                                                            accountId = source.accountId,
                                                            remotePath = source.remotePath,
                                                            size = source.size,
                                                            etag = source.etag,
                                                            lastModified = source.lastModified,
                                                            onOpenSucceeded = {
                                                                libraryViewModel.markOpened(item.item.id)
                                                            },
                                                        )
                                                    }
                                                }
                                            }
                                        },
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
                                        onOpenComicDownload = ::openDownloadRecordComic,
                                        onPlayVideoDownload = videoActions::playVideoDownloadRecord,
                                        onCancelActiveDownload = ::cancelActiveDownload,
                                        onRemoveComicRecord = ::removeComicDownloadRecord,
                                        onRemoveVideoRecord = ::removeVideoDownloadRecord,
                                        onDeleteComicFile = ::deleteComicDownloadFile,
                                        onDeleteVideoFile = ::deleteVideoDownloadRecord,
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
                                        onClearCacheCategory = { category ->
                                            scope.launch {
                                                val result = withContext(Dispatchers.IO) {
                                                    clearComicCacheCategory(
                                                        cacheDir = context.cacheDir,
                                                        category = category,
                                                        codeCacheDir = context.codeCacheDir,
                                                        externalCacheDirs = context.externalCacheDirs.filterNotNull(),
                                                    )
                                                }
                                                cacheAnalysis = withContext(Dispatchers.IO) {
                                                    analyzeComicCache(
                                                        cacheDir = context.cacheDir,
                                                        codeCacheDir = context.codeCacheDir,
                                                        externalCacheDirs = context.externalCacheDirs.filterNotNull(),
                                                    )
                                                }
                                                cacheActionMessage =
                                                    "已清理 ${category.cacheLabel()}：${result.filesDeleted} 个文件，释放 ${formatCacheSize(result.bytesDeleted)}"
                                            }
                                        },
                                        onClearAllCache = {
                                            scope.launch {
                                                val result = withContext(Dispatchers.IO) {
                                                    clearComicCache(
                                                        cacheDir = context.cacheDir,
                                                        codeCacheDir = context.codeCacheDir,
                                                        externalCacheDirs = context.externalCacheDirs.filterNotNull(),
                                                    )
                                                }
                                                cacheAnalysis = withContext(Dispatchers.IO) {
                                                    analyzeComicCache(
                                                        cacheDir = context.cacheDir,
                                                        codeCacheDir = context.codeCacheDir,
                                                        externalCacheDirs = context.externalCacheDirs.filterNotNull(),
                                                    )
                                                }
                                                cacheActionMessage =
                                                    "已清理全部缓存：${result.filesDeleted} 个文件，释放 ${formatCacheSize(result.bytesDeleted)}"
                                            }
                                        },
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
