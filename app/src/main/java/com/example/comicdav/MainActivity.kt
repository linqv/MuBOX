package com.example.comicdav

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.comicdav.data.AppSettings
import com.example.comicdav.data.ComicCacheAnalysis
import com.example.comicdav.data.ComicCacheCategory
import com.example.comicdav.data.DownloadRecord
import com.example.comicdav.data.ReaderLoggingMode
import com.example.comicdav.data.SavedWebDavAccount
import com.example.comicdav.data.VideoDownloadRecord
import com.example.comicdav.data.filedirectory.FileDirectorySourceType
import com.example.comicdav.data.analyzeComicCache
import com.example.comicdav.data.clearComicCache
import com.example.comicdav.data.clearComicCacheCategory
import com.example.comicdav.data.formatCacheSize
import com.example.comicdav.feature.filedirectory.FileDirectoryBrowserItem
import com.example.comicdav.feature.filedirectory.FileDirectoryScreen
import com.example.comicdav.data.filedirectory.FileDirectorySourceEntity
import com.example.comicdav.feature.filedirectory.FileDirectoryViewModel
import com.example.comicdav.data.library.LibraryItemWithSources
import com.example.comicdav.data.library.SourceType
import com.example.comicdav.data.videolibrary.VideoLibraryItemWithSources
import com.example.comicdav.data.videolibrary.VideoSourceType
import com.example.comicdav.feature.library.LibraryScreen
import com.example.comicdav.feature.library.LibraryViewModel
import com.example.comicdav.feature.reader.ReaderDiagnosticLog
import com.example.comicdav.feature.reader.ReaderScreen
import com.example.comicdav.feature.reader.ReaderViewModel
import com.example.comicdav.feature.reader.OpenComicUseCase
import com.example.comicdav.feature.reader.ReaderPageCache
import com.example.comicdav.feature.reader.installReaderImageLoader
import com.example.comicdav.feature.reader.localComicCacheKey
import com.example.comicdav.feature.reader.readerImageFormatCacheKey
import com.example.comicdav.feature.settings.SettingsScreen
import com.example.comicdav.feature.settings.pageCacheLimitBytesForSettings
import com.example.comicdav.feature.downloads.DownloadsScreen
import com.example.comicdav.feature.videolibrary.VideoLibraryScreen
import com.example.comicdav.feature.videolibrary.VideoLibraryViewModel
import com.example.comicdav.feature.webdav.DownloadProgressUi
import com.example.comicdav.feature.webdav.WEB_DAV_STATUS_CONNECTED
import com.example.comicdav.feature.webdav.WebDavAccountScreen
import com.example.comicdav.feature.webdav.WebDavBrowserScreen
import com.example.comicdav.feature.webdav.WebDavViewModel
import com.example.comicdav.network.RemoteFileInfo
import com.example.comicdav.network.WebDavItem
import com.example.comicdav.ui.ComicDavTheme
import com.example.comicdav.video.LocalVideoOpenRequest
import com.example.comicdav.video.MediaKind
import com.example.comicdav.video.VideoSubtitleOpenRequest
import com.example.comicdav.video.WebDavSubtitleOpenRequest
import com.example.comicdav.video.WebDavVideoOpenRequest
import com.example.comicdav.video.findSidecarSubtitles
import com.example.comicdav.video.mediaKindFor
import com.example.comicdav.video.mimeTypeForMediaFileName
import com.example.comicdav.video.player.VideoEpisode
import com.example.comicdav.video.player.VideoEpisodeQueue
import com.example.comicdav.video.player.VideoPlayerActivity
import com.example.comicdav.video.proxy.VideoProxyManager
import com.example.comicdav.video.proxy.VideoProxySettings
import com.example.comicdav.video.proxy.startWebDavVideoPlayback
import com.example.comicdav.webdav.decodeWebDavPathForDisplay
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    private var previousCrashHandler: Thread.UncaughtExceptionHandler? = null
    private var readerCrashHandler: Thread.UncaughtExceptionHandler? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        installReaderImageLoader(applicationContext)
        installReaderCrashLogger()
        setContent { ComicDavApp() }
    }

    override fun onDestroy() {
        val installedHandler = readerCrashHandler
        if (installedHandler != null && Thread.getDefaultUncaughtExceptionHandler() === installedHandler) {
            Thread.setDefaultUncaughtExceptionHandler(previousCrashHandler)
        }
        readerCrashHandler = null
        previousCrashHandler = null
        super.onDestroy()
    }

    private fun installReaderCrashLogger() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        previousCrashHandler = previous
        val handler = Thread.UncaughtExceptionHandler { thread, throwable ->
            ReaderDiagnosticLog.errorBlocking("uncaught thread=${thread.name}", throwable)
            previous?.uncaughtException(thread, throwable)
        }
        readerCrashHandler = handler
        Thread.setDefaultUncaughtExceptionHandler(handler)
    }
}

@Composable
fun ComicDavApp() {
    val webDavViewModel: WebDavViewModel = viewModel()
    val readerViewModel: ReaderViewModel = viewModel()
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    val container = remember(context) { AppContainer(context) }
    LaunchedEffect(Unit) {
        container.webDavAccountStore.migratePlaintextPasswords()
    }
    val libraryRepository = container.libraryRepository
    val videoLibraryRepository = container.videoLibraryRepository
    val localComicOpener = container.localComicOpener
    val localDirectoryReader = container.localDirectoryReader
    val libraryViewModel: LibraryViewModel = viewModel(
        factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                return LibraryViewModel(libraryRepository) as T
            }
        },
    )
    val videoLibraryViewModel: VideoLibraryViewModel = viewModel(
        factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                return VideoLibraryViewModel(videoLibraryRepository) as T
            }
        },
    )
    val fileDirectoryViewModel: FileDirectoryViewModel = viewModel(
        factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                return FileDirectoryViewModel(container.fileDirectoryRepository, container.localDirectoryReader) as T
            }
        },
    )
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
    var selectedWebDavFile by remember { mutableStateOf<WebDavItem?>(null) }
    var selectedDirectoryComic by remember { mutableStateOf<FileDirectoryBrowserItem?>(null) }
    var selectedDirectoryVideo by remember { mutableStateOf<FileDirectoryBrowserItem?>(null) }
    var selectedLibraryItem by remember { mutableStateOf<LibraryItemWithSources?>(null) }
    var selectedVideoLibraryItem by remember { mutableStateOf<VideoLibraryItemWithSources?>(null) }
    var pendingLocalVideoOpen by remember { mutableStateOf<LocalVideoOpenRequest?>(null) }
    var pendingWebDavVideoOpen by remember { mutableStateOf<WebDavVideoOpenRequest?>(null) }
    var localOpenError by remember { mutableStateOf<String?>(null) }
    var webDavActionMessage by remember { mutableStateOf<String?>(null) }
    var downloadProgress by remember { mutableStateOf<DownloadProgressUi?>(null) }
    var activeDownloadJob by remember { mutableStateOf<Job?>(null) }
    var cacheAnalysis by remember { mutableStateOf(ComicCacheAnalysis()) }
    var cacheActionMessage by remember { mutableStateOf<String?>(null) }
    var logFolderUriText by rememberSaveable { mutableStateOf(loadReaderLogFolderUri(context)) }
    var dataFolderUriText by rememberSaveable { mutableStateOf<String?>(null) }
    var isDataFolderLoading by remember { mutableStateOf(true) }
    val remoteCache = container.remoteCache
    val coverExtractor = container.coverExtractor
    val videoThumbnailExtractor = container.videoThumbnailExtractor
    val progressStore = container.progressStore
    val dataFolderStore = container.dataFolderStore
    val appSettingsStore = container.appSettingsStore
    val webDavAccountStore = container.webDavAccountStore
    val downloadRecordStore = container.downloadRecordStore
    val videoDownloadStore = container.videoDownloadStore
    val appSettings by appSettingsStore.settings.collectAsState(initial = AppSettings(videoResumeEnabled = false))
    val downloadRecords by downloadRecordStore.records.collectAsState(initial = emptyList())
    val videoDownloadRecords by videoDownloadStore.records.collectAsState(initial = emptyList())
    fun clearSelection() {
        selectedWebDavFile = null
        selectedDirectoryComic = null
        selectedDirectoryVideo = null
        selectedLibraryItem = null
        selectedVideoLibraryItem = null
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
        activeDownloadJob?.cancel()
        activeDownloadJob = null
        downloadProgress = null
        localOpenError = null
        webDavActionMessage = "已取消下载"
    }
    fun reportDownloadProgress(downloaded: Long, total: Long) {
        scope.launch { downloadProgress = DownloadProgressUi(downloaded, total) }
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
    val dataFolderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        runCatching {
            context.contentResolver.takePersistableUriPermission(uri, flags)
        }.onFailure { error ->
            ReaderDiagnosticLog.error("data_folder_permission_failed uri=$uri", error)
        }
        scope.launch {
            dataFolderStore.saveFolderUri(uri.toString())
            dataFolderUriText = uri.toString()
            if (logFolderUriText.isNullOrBlank()) {
                logFolderUriText = uri.toString()
            }
        }
    }
    val logFolderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri == null) {
            ReaderDiagnosticLog.event("log_folder_cancelled")
            return@rememberLauncherForActivityResult
        }
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        runCatching {
            context.contentResolver.takePersistableUriPermission(uri, flags)
        }.onFailure { error ->
            ReaderDiagnosticLog.error("log_folder_permission_failed uri=$uri", error)
        }
        saveReaderLogFolderUri(context, uri)
        logFolderUriText = uri.toString()
        startReaderLogFile(context, logFolderUriText, scope, appSettings.readerLoggingMode != ReaderLoggingMode.OFF)
        ReaderDiagnosticLog.event("log_folder_selected uri=$uri")
    }
    val localDirectoryPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        ReaderDiagnosticLog.event("local_directory_selected uri=$uri")
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        runCatching {
            context.contentResolver.takePersistableUriPermission(uri, flags)
        }.onFailure { error ->
            ReaderDiagnosticLog.error("local_directory_permission_failed uri=$uri", error)
        }
        fileDirectoryViewModel.addLocalDirectory(
            displayName = queryDirectoryDisplayName(context, uri),
            treeUri = uri.toString(),
        )
    }
    val videoPlayerLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        forceMainPortraitState.value = true
    }

    fun openVideoPlayer(intent: Intent) {
        videoPlayerLauncher.launch(intent)
    }

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

    fun openLocalDirectoryVideo(item: FileDirectoryBrowserItem) {
        pendingWebDavVideoOpen = null
        val episodeQueue = buildLocalDirectoryEpisodeQueue(
            entries = fileDirectoryViewModel.playbackDirectoryEntries(),
            currentItem = item,
        )
        val request = episodeQueue?.currentEpisode?.localRequest
            ?: localVideoEpisodeRequest(item, fileDirectoryUiState.entries)
        pendingLocalVideoOpen = request
        localOpenError = null
        openVideoPlayer(
            VideoPlayerActivity.localIntent(
                context = context,
                request = request,
                resumeEnabled = appSettings.videoResumeEnabled,
                videoOutputMode = appSettings.videoOutputMode,
                gpuApiMode = appSettings.gpuApiMode,
                videoDecoderMode = appSettings.videoDecoderMode,
                mpvProfileMode = appSettings.mpvProfileMode,
                controlsAutoHideMillis = appSettings.videoControlsAutoHideMillis,
                playerOrientationMode = appSettings.videoPlayerOrientationMode,
                proxyDebugInfoEnabled = appSettings.videoPlayerProxyDebugInfoEnabled,
                videoBackgroundMode = appSettings.videoBackgroundMode,
                anime4kEnabled = appSettings.anime4kEnabled,
                anime4kMode = appSettings.anime4kMode,
                anime4kQuality = appSettings.anime4kQuality,
                episodeQueue = episodeQueue,
            ),
        )
    }

    suspend fun resolveWebDavAccountForPlayback(accountId: String): SavedWebDavAccount? {
        webDavAccountStore.loadAccount(accountId)?.let { return it }
        val activeAccountId = webDavViewModel.activeAccountId() ?: webDavViewModel.accountId()
        val baseUrl = uiState.baseUrl.trim()
        if (activeAccountId != accountId || baseUrl.isBlank()) return null
        return SavedWebDavAccount(
            accountId = accountId,
            baseUrl = baseUrl,
            username = uiState.username,
            password = uiState.password,
        )
    }

    fun currentVideoProxySettings(): VideoProxySettings =
        VideoProxySettings(
            seekOptimizationEnabled = appSettings.videoSeekOptimizationEnabled,
            forwardPrefetchMode = appSettings.videoForwardPrefetchMode,
            diagnosticsMode = appSettings.videoProxyDiagnosticsMode,
        )

    suspend fun extractLocalVideoThumbnail(
        uri: String,
        size: Long?,
        lastModified: Long?,
    ): String? =
        videoThumbnailExtractor.extractFromContentUri(
            context = context,
            uri = Uri.parse(uri),
            stableKey = "local:$uri:${size ?: -1}:${lastModified ?: -1}",
        )

    suspend fun extractWebDavVideoThumbnail(request: WebDavVideoOpenRequest): String? {
        val account = resolveWebDavAccountForPlayback(request.accountId)
            ?: error("缺少 WebDAV 账号，请重新连接后再提取缩略图")
        val session = VideoProxyManager.open(
            request = request.copy(subtitles = emptyList()),
            account = account,
            proxySettings = currentVideoProxySettings(),
        )
        return try {
            videoThumbnailExtractor.extractFromUrl(
                url = session.url,
                stableKey = "webdav:${request.accountId}:${request.remotePath}:${request.size ?: -1}:${request.etag.orEmpty()}:${request.lastModified ?: -1}",
            )
        } finally {
            VideoProxyManager.close(session.streamIds)
        }
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

    fun favoriteLocalDirectoryVideo(item: FileDirectoryBrowserItem) {
        scope.launch {
            runCatching {
                val thumbnailPath = if (appSettings.videoLibraryThumbnailsEnabled) {
                    runCatching {
                        extractLocalVideoThumbnail(
                            uri = item.uri,
                            size = item.size,
                            lastModified = item.lastModified,
                        )
                    }.onFailure { error ->
                        ReaderDiagnosticLog.error("extract_local_video_thumbnail_failed uri=${item.uri}", error)
                    }.getOrNull()
                } else {
                    null
                }
                videoLibraryRepository.addLocalVideo(
                    uri = item.uri,
                    fileName = item.name,
                    size = item.size,
                    lastModified = item.lastModified,
                    thumbnailPath = thumbnailPath,
                )
            }.fold(
                onSuccess = {
                    selectedDirectoryVideo = null
                    videoLibraryViewModel.showMessage("已将 ${item.name} 加入影视库")
                    fileDirectoryViewModel.showMessage("已将 ${item.name} 加入影视库")
                },
                onFailure = { error ->
                    ReaderDiagnosticLog.error("favorite_local_directory_video_failed uri=${item.uri}", error)
                    videoLibraryViewModel.showError(error.message ?: "加入影视库失败")
                    fileDirectoryViewModel.showError(error.message ?: "加入影视库失败")
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

    fun webDavVideoRequestForItem(item: WebDavItem): WebDavVideoOpenRequest {
        val accountId = webDavViewModel.activeAccountId() ?: webDavViewModel.accountId()
        return WebDavVideoOpenRequest(
            accountId = accountId,
            remotePath = item.path,
            displayName = item.name,
            size = item.size,
            etag = item.etag,
            lastModified = item.lastModified,
            mimeType = mimeTypeForMediaFileName(item.name),
            subtitles = emptyList(),
        )
    }

    fun favoriteWebDavVideo(item: WebDavItem) {
        val accountId = webDavViewModel.activeAccountId() ?: webDavViewModel.accountId()
        if (accountId.substringBefore("|").isBlank()) {
            webDavActionMessage = null
            localOpenError = "请先连接 WebDAV，再加入影视库"
            return
        }
        localOpenError = null
        webDavActionMessage = null
        scope.launch {
            runCatching {
                val request = webDavVideoRequestForItem(item)
                val thumbnailPath = if (appSettings.videoLibraryThumbnailsEnabled) {
                    runCatching {
                        extractWebDavVideoThumbnail(request)
                    }.onFailure { error ->
                        ReaderDiagnosticLog.error("extract_webdav_video_thumbnail_failed path=${item.path}", error)
                    }.getOrNull()
                } else {
                    null
                }
                videoLibraryRepository.addWebDavVideo(
                    accountId = accountId,
                    remotePath = item.path,
                    fileName = item.name,
                    size = item.size,
                    etag = item.etag,
                    lastModified = item.lastModified,
                    thumbnailPath = thumbnailPath,
                )
            }.fold(
                onSuccess = {
                    selectedWebDavFile = null
                    webDavActionMessage = "已将 ${item.name} 加入影视库"
                    videoLibraryViewModel.showMessage("已将 ${item.name} 加入影视库")
                    fileDirectoryViewModel.showMessage("已将 ${item.name} 加入影视库")
                },
                onFailure = { error ->
                    localOpenError = error.message ?: "添加 WebDAV 视频失败"
                    ReaderDiagnosticLog.error("add_webdav_video_library_failed path=${item.path}", error)
                    videoLibraryViewModel.showError(error.message ?: "添加 WebDAV 视频失败")
                    fileDirectoryViewModel.showError(error.message ?: "添加 WebDAV 视频失败")
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
        activeDownloadJob?.cancel()
        downloadProgress = null
        localOpenError = null
        webDavActionMessage = null
        val job = scope.launch {
            try {
                val info = item.size?.let { knownSize ->
                    RemoteFileInfo(
                        path = item.path,
                        size = knownSize,
                        etag = item.etag,
                        lastModified = item.lastModified,
                        supportsRange = true,
                    )
                } ?: client.head(item.path)
                val progressThrottler = DownloadProgressThrottler()
                val record = downloadWebDavComicRecordToDataFolder(
                    context,
                    Uri.parse(folderUriText),
                    client,
                    accountId,
                    item.path,
                    item.name,
                    info,
                    System.currentTimeMillis(),
                ) { downloaded, total ->
                    if (progressThrottler.shouldReport(downloaded, total)) reportDownloadProgress(downloaded, total)
                }
                downloadRecordStore.addRecord(record)
                downloadProgress = null
                webDavActionMessage = "已下载 ${item.name} 到数据文件夹"
                fileDirectoryViewModel.showMessage("已下载 ${item.name} 到数据文件夹")
            } catch (error: CancellationException) {
                downloadProgress = null
                if (activeDownloadJob == currentCoroutineContext().job) {
                    webDavActionMessage = "已取消下载"
                }
                throw error
            } catch (error: Throwable) {
                downloadProgress = null
                localOpenError = error.message ?: "下载到本地失败"
                ReaderDiagnosticLog.error("download_webdav_comic_failed path=${item.path}", error)
                fileDirectoryViewModel.showError(error.message ?: "下载到本地失败")
            } finally {
                if (activeDownloadJob == currentCoroutineContext().job) {
                    activeDownloadJob = null
                }
            }
        }
        activeDownloadJob = job
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
        activeDownloadJob?.cancel()
        downloadProgress = null
        localOpenError = null
        webDavActionMessage = null
        val job = scope.launch {
            try {
                val info = item.size?.let { knownSize ->
                    RemoteFileInfo(
                        path = item.path,
                        size = knownSize,
                        etag = item.etag,
                        lastModified = item.lastModified,
                        supportsRange = true,
                    )
                } ?: client.head(item.path)
                val progressThrottler = DownloadProgressThrottler()
                val localUri = downloadWebDavVideoToDataFolder(
                    context = context,
                    folderTreeUri = Uri.parse(folderUriText),
                    client = client,
                    accountId = accountId,
                    remotePath = item.path,
                    fileName = item.name,
                    expectedSize = info.size,
                ) { downloaded, total ->
                    if (progressThrottler.shouldReport(downloaded, total)) reportDownloadProgress(downloaded, total)
                }
                videoDownloadStore.addRecord(
                    VideoDownloadRecord(
                        fileName = item.name,
                        accountId = accountId,
                        remotePath = item.path,
                        localUri = localUri,
                        sizeBytes = info.size,
                        downloadedAtMillis = System.currentTimeMillis(),
                    ),
                )
                downloadProgress = null
                selectedWebDavFile = null
                webDavActionMessage = "已下载 ${item.name} 到数据文件夹"
                fileDirectoryViewModel.showMessage("已下载 ${item.name} 到数据文件夹")
            } catch (error: CancellationException) {
                downloadProgress = null
                if (activeDownloadJob == currentCoroutineContext().job) {
                    webDavActionMessage = "已取消下载"
                }
                throw error
            } catch (error: Throwable) {
                downloadProgress = null
                localOpenError = error.message ?: "下载视频失败"
                ReaderDiagnosticLog.error("download_webdav_video_failed path=${item.path}", error)
                fileDirectoryViewModel.showError(error.message ?: "下载视频失败")
            } finally {
                if (activeDownloadJob == currentCoroutineContext().job) {
                    activeDownloadJob = null
                }
            }
        }
        activeDownloadJob = job
    }

    suspend fun resolveWebDavClientForAccount(accountId: String): com.example.comicdav.network.WebDavClient? {
        val activeClient = webDavViewModel.activeClient()
        if (activeClient != null && webDavViewModel.activeAccountId() == accountId) {
            return activeClient
        }
        val savedAccount = webDavAccountStore.loadAccount(accountId) ?: return null
        webDavViewModel.connectToSavedSource(
            baseUrl = savedAccount.baseUrl,
            username = savedAccount.username,
            password = savedAccount.password,
            path = "/",
        )
        return webDavViewModel.activeClient()
    }

    fun openWebDavVideo(item: WebDavItem) {
        val accountId = webDavViewModel.activeAccountId() ?: webDavViewModel.accountId()
        val episodeQueue = buildWebDavDirectoryEpisodeQueue(
            accountId = accountId,
            items = webDavViewModel.playbackDirectoryItems(),
            currentItem = item,
        )
        val request = episodeQueue?.currentEpisode?.webDavRequest
            ?: webDavVideoEpisodeRequest(accountId, item, uiState.items)
        pendingLocalVideoOpen = null
        pendingWebDavVideoOpen = request
        localOpenError = null
        webDavActionMessage = "已进入内部视频打开流程：${item.name}"
        scope.launch {
            runCatching {
                val account = resolveWebDavAccountForPlayback(accountId)
                    ?: error("缺少 WebDAV 账号，请重新连接后再打开视频")
                startWebDavVideoPlayback(
                    request = request,
                    account = account,
                    proxySettings = currentVideoProxySettings(),
                ) { session ->
                    openVideoPlayer(
                        VideoPlayerActivity.webDavIntent(
                            context = context,
                            request = request,
                            uri = session.url,
                            subtitleUrls = session.subtitleUrls,
                            streamIds = session.streamIds,
                            resumeEnabled = appSettings.videoResumeEnabled,
                            videoOutputMode = appSettings.videoOutputMode,
                            gpuApiMode = appSettings.gpuApiMode,
                            videoDecoderMode = appSettings.videoDecoderMode,
                            mpvProfileMode = appSettings.mpvProfileMode,
                            controlsAutoHideMillis = appSettings.videoControlsAutoHideMillis,
                            playerOrientationMode = appSettings.videoPlayerOrientationMode,
                            proxyDebugInfoEnabled = appSettings.videoPlayerProxyDebugInfoEnabled,
                            videoBackgroundMode = appSettings.videoBackgroundMode,
                            anime4kEnabled = appSettings.anime4kEnabled,
                            anime4kMode = appSettings.anime4kMode,
                            anime4kQuality = appSettings.anime4kQuality,
                            episodeQueue = episodeQueue,
                        ),
                    )
                }
            }.onFailure { error ->
                localOpenError = error.message ?: "打开视频失败"
                webDavActionMessage = null
            }
        }
    }

    fun downloadRemoteComicToLocal(
        accountId: String,
        remotePath: String,
        fileName: String,
        size: Long?,
        etag: String?,
        lastModified: Long?,
        onSuccess: (String) -> Unit,
        onFailure: (String) -> Unit,
    ) {
        val folderUriText = dataFolderUriText
        if (folderUriText.isNullOrBlank()) {
            onFailure("请先选择 MuBOX 数据文件夹，再下载漫画")
            return
        }
        activeDownloadJob?.cancel()
        downloadProgress = null
        localOpenError = null
        webDavActionMessage = null
        val job = scope.launch {
            try {
                val client = resolveWebDavClientForAccount(accountId) ?: error("请先连接 $accountId，再下载漫画")
                val info = size?.let { knownSize ->
                    RemoteFileInfo(
                        path = remotePath,
                        size = knownSize,
                        etag = etag,
                        lastModified = lastModified,
                        supportsRange = true,
                    )
                } ?: client.head(remotePath)
                val progressThrottler = DownloadProgressThrottler()
                val record = downloadWebDavComicRecordToDataFolder(
                    context,
                    Uri.parse(folderUriText),
                    client,
                    accountId,
                    remotePath,
                    fileName,
                    info,
                    System.currentTimeMillis(),
                ) { downloaded, total ->
                    if (progressThrottler.shouldReport(downloaded, total)) reportDownloadProgress(downloaded, total)
                }
                downloadRecordStore.addRecord(record)
                downloadProgress = null
                onSuccess("已下载 $fileName 到数据文件夹")
            } catch (error: CancellationException) {
                downloadProgress = null
                if (activeDownloadJob == currentCoroutineContext().job) {
                    webDavActionMessage = "已取消下载"
                }
                throw error
            } catch (error: Throwable) {
                downloadProgress = null
                ReaderDiagnosticLog.error("download_remote_comic_failed path=$remotePath", error)
                onFailure(error.message ?: "下载到本地失败")
            } finally {
                if (activeDownloadJob == currentCoroutineContext().job) {
                    activeDownloadJob = null
                }
            }
        }
        activeDownloadJob = job
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
            onSuccess = { message ->
                libraryViewModel.showMessage(message)
            },
            onFailure = { message ->
                libraryViewModel.showError(message)
            },
        )
    }

    fun removeLibraryItem(item: LibraryItemWithSources) {
        scope.launch {
            runCatching {
                libraryRepository.removeComic(item.item.id)
            }.fold(
                onSuccess = {
                    selectedLibraryItem = null
                    libraryViewModel.showMessage("已将 ${item.item.displayName} 移出书架")
                },
                onFailure = { error ->
                    libraryViewModel.showError(error.message ?: "移出书架失败")
                },
            )
        }
    }

    suspend fun localVideoLibrarySubtitles(
        videoUri: String,
        videoFileName: String,
    ): List<VideoSubtitleOpenRequest> {
        val parentUri = parentDocumentUriForLocalVideo(Uri.parse(videoUri)) ?: return emptyList()
        val siblings = runCatching {
            localDirectoryReader.listChildren(parentUri.toString())
        }.onFailure { error ->
            ReaderDiagnosticLog.error("local_video_library_subtitles_failed uri=$videoUri", error)
        }.getOrDefault(emptyList())
        return findSidecarSubtitles(
            videoFileName = videoFileName,
            candidates = siblings,
            nameOf = FileDirectoryBrowserItem::name,
            isDirectoryOf = FileDirectoryBrowserItem::isDirectory,
        ).map { subtitle ->
            VideoSubtitleOpenRequest(
                uri = subtitle.uri,
                displayName = subtitle.name,
            )
        }
    }

    suspend fun webDavVideoLibrarySubtitles(
        accountId: String,
        remotePath: String,
        videoFileName: String,
    ): List<WebDavSubtitleOpenRequest> {
        val client = resolveWebDavClientForAccount(accountId) ?: return emptyList()
        val parentPath = parentWebDavDirectoryPath(remotePath)
        val siblings = runCatching {
            client.list(parentPath)
        }.onFailure { error ->
            ReaderDiagnosticLog.error("webdav_video_library_subtitles_failed path=$remotePath", error)
        }.getOrDefault(emptyList())
        return findSidecarSubtitles(
            videoFileName = videoFileName,
            candidates = siblings,
            nameOf = WebDavItem::name,
            isDirectoryOf = WebDavItem::isDirectory,
        ).map { subtitle ->
            WebDavSubtitleOpenRequest(
                remotePath = subtitle.path,
                displayName = subtitle.name,
                size = subtitle.size,
                etag = subtitle.etag,
                lastModified = subtitle.lastModified,
                mimeType = mimeTypeForMediaFileName(subtitle.name),
            )
        }
    }

    fun openVideoLibraryItem(item: VideoLibraryItemWithSources) {
        when (item.item.sourceType) {
            VideoSourceType.LOCAL -> {
                val source = item.localSource ?: run {
                    videoLibraryViewModel.showError("缺少本地视频来源")
                    return
                }
                scope.launch {
                    val request = LocalVideoOpenRequest(
                        uri = source.uri,
                        displayName = source.fileName,
                        size = source.size,
                        lastModified = source.lastModified,
                        subtitles = localVideoLibrarySubtitles(
                            videoUri = source.uri,
                            videoFileName = source.fileName,
                        ),
                    )
                    videoLibraryViewModel.markOpened(item.item.id)
                    openVideoPlayer(
                        VideoPlayerActivity.localIntent(
                            context = context,
                            request = request,
                            resumeEnabled = appSettings.videoResumeEnabled,
                            videoOutputMode = appSettings.videoOutputMode,
                            gpuApiMode = appSettings.gpuApiMode,
                            videoDecoderMode = appSettings.videoDecoderMode,
                            mpvProfileMode = appSettings.mpvProfileMode,
                            controlsAutoHideMillis = appSettings.videoControlsAutoHideMillis,
                            playerOrientationMode = appSettings.videoPlayerOrientationMode,
                            proxyDebugInfoEnabled = appSettings.videoPlayerProxyDebugInfoEnabled,
                            videoBackgroundMode = appSettings.videoBackgroundMode,
                            anime4kEnabled = appSettings.anime4kEnabled,
                            anime4kMode = appSettings.anime4kMode,
                            anime4kQuality = appSettings.anime4kQuality,
                        ),
                    )
                }
            }
            VideoSourceType.WEBDAV -> {
                val source = item.webDavSource ?: run {
                    videoLibraryViewModel.showError("缺少 WebDAV 视频来源")
                    return
                }
                val request = WebDavVideoOpenRequest(
                    accountId = source.accountId,
                    remotePath = source.remotePath,
                    displayName = source.fileName,
                    size = source.size,
                    etag = source.etag,
                    lastModified = source.lastModified,
                    mimeType = mimeTypeForMediaFileName(source.fileName),
                )
                scope.launch {
                    runCatching {
                        val subtitles = webDavVideoLibrarySubtitles(
                            accountId = source.accountId,
                            remotePath = source.remotePath,
                            videoFileName = source.fileName,
                        )
                        val playbackRequest = request.copy(subtitles = subtitles)
                        val account = resolveWebDavAccountForPlayback(source.accountId)
                            ?: error("缺少 WebDAV 账号，请重新连接后再打开视频")
                        startWebDavVideoPlayback(
                            request = playbackRequest,
                            account = account,
                            proxySettings = currentVideoProxySettings(),
                        ) { session ->
                            videoLibraryViewModel.markOpened(item.item.id)
                            openVideoPlayer(
                                VideoPlayerActivity.webDavIntent(
                                    context = context,
                                    request = playbackRequest,
                                    uri = session.url,
                                    subtitleUrls = session.subtitleUrls,
                                    streamIds = session.streamIds,
                                    resumeEnabled = appSettings.videoResumeEnabled,
                                    videoOutputMode = appSettings.videoOutputMode,
                                    gpuApiMode = appSettings.gpuApiMode,
                                    videoDecoderMode = appSettings.videoDecoderMode,
                                    mpvProfileMode = appSettings.mpvProfileMode,
                                    controlsAutoHideMillis = appSettings.videoControlsAutoHideMillis,
                                    playerOrientationMode = appSettings.videoPlayerOrientationMode,
                                    proxyDebugInfoEnabled = appSettings.videoPlayerProxyDebugInfoEnabled,
                                    videoBackgroundMode = appSettings.videoBackgroundMode,
                                    anime4kEnabled = appSettings.anime4kEnabled,
                                    anime4kMode = appSettings.anime4kMode,
                                    anime4kQuality = appSettings.anime4kQuality,
                                ),
                            )
                        }
                    }.onFailure { error ->
                        videoLibraryViewModel.showError(error.message ?: "打开视频失败")
                    }
                }
            }
        }
    }

    fun removeVideoLibraryItem(item: VideoLibraryItemWithSources) {
        scope.launch {
            runCatching {
                item.item.thumbnailPath?.let { path ->
                    withContext(Dispatchers.IO) {
                        File(path).takeIf { it.isFile }?.delete()
                    }
                }
                videoLibraryRepository.removeVideo(item.item.id)
            }.fold(
                onSuccess = {
                    selectedVideoLibraryItem = null
                    videoLibraryViewModel.showMessage("已将 ${item.item.displayName} 移出影视库")
                },
                onFailure = { error ->
                    videoLibraryViewModel.showError(error.message ?: "移出影视库失败")
                },
            )
        }
    }

    fun refreshVideoLibraryThumbnail(item: VideoLibraryItemWithSources) {
        scope.launch {
            runCatching {
                when (item.item.sourceType) {
                    VideoSourceType.LOCAL -> {
                        val source = item.localSource ?: error("缺少本地视频来源")
                        extractLocalVideoThumbnail(
                            uri = source.uri,
                            size = source.size,
                            lastModified = source.lastModified,
                        ) ?: error("未能提取视频缩略图")
                    }
                    VideoSourceType.WEBDAV -> {
                        val source = item.webDavSource ?: error("缺少 WebDAV 视频来源")
                        extractWebDavVideoThumbnail(
                            WebDavVideoOpenRequest(
                                accountId = source.accountId,
                                remotePath = source.remotePath,
                                displayName = source.fileName,
                                size = source.size,
                                etag = source.etag,
                                lastModified = source.lastModified,
                                mimeType = mimeTypeForMediaFileName(source.fileName),
                            ),
                        ) ?: error("未能提取视频缩略图")
                    }
                }
            }.fold(
                onSuccess = { thumbnailPath ->
                    videoLibraryRepository.updateThumbnailPath(item.item.id, thumbnailPath)
                    selectedVideoLibraryItem = null
                    videoLibraryViewModel.showMessage("已重新提取 ${item.item.displayName} 的缩略图")
                },
                onFailure = { error ->
                    ReaderDiagnosticLog.error("refresh_video_thumbnail_failed id=${item.item.id}", error)
                    videoLibraryViewModel.showError(error.message ?: "重新提取缩略图失败")
                },
            )
        }
    }

    fun deleteVideoLibraryThumbnail(item: VideoLibraryItemWithSources) {
        scope.launch {
            runCatching {
                item.item.thumbnailPath?.let { path ->
                    withContext(Dispatchers.IO) {
                        File(path).takeIf { it.isFile }?.delete()
                    }
                }
                videoLibraryRepository.updateThumbnailPath(item.item.id, null)
            }.fold(
                onSuccess = {
                    selectedVideoLibraryItem = null
                    videoLibraryViewModel.showMessage("已删除 ${item.item.displayName} 的缩略图")
                },
                onFailure = { error ->
                    videoLibraryViewModel.showError(error.message ?: "删除缩略图失败")
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
                val client = resolveWebDavClientForAccount(source.accountId) ?: error("请先连接 ${source.accountId}，再重新获取封面")
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
                    selectedLibraryItem = null
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

    fun addDownloadRecordToLibrary(record: DownloadRecord) {
        val accountId = record.accountId
            ?: webDavViewModel.activeAccountId()
            ?: webDavViewModel.accountId().takeIf { it.substringBefore("|").isNotBlank() }
        if (accountId.isNullOrBlank()) {
            localOpenError = "这条下载记录缺少 WebDAV 账号，请先连接对应账号"
            return
        }
        scope.launch {
            runCatching {
                libraryRepository.addWebDavComic(
                    accountId = accountId,
                    remotePath = record.remotePath,
                    fileName = record.fileName,
                    size = record.sizeBytes,
                )
            }.fold(
                onSuccess = {
                    libraryViewModel.showMessage("已将 ${record.fileName} 加入书架")
                },
                onFailure = { error ->
                    ReaderDiagnosticLog.error("add_download_record_to_library_failed path=${record.remotePath}", error)
                    localOpenError = error.message ?: "加入书架失败"
                },
            )
        }
    }

    fun deleteDownloadRecord(record: DownloadRecord) {
        scope.launch {
            downloadRecordStore.removeRecord(record)
        }
    }

    fun removeComicDownloadRecord(record: DownloadRecord) {
        deleteDownloadRecord(record)
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

    fun playVideoDownloadRecord(record: VideoDownloadRecord) {
        pendingWebDavVideoOpen = null
        val request = LocalVideoOpenRequest(
            uri = record.localUri,
            displayName = record.fileName,
            size = record.sizeBytes.takeIf { it > 0L },
            lastModified = record.downloadedAtMillis,
        )
        pendingLocalVideoOpen = request
        localOpenError = null
        runCatching {
            openVideoPlayer(
                VideoPlayerActivity.localIntent(
                    context = context,
                    request = request,
                    resumeEnabled = appSettings.videoResumeEnabled,
                    videoOutputMode = appSettings.videoOutputMode,
                    gpuApiMode = appSettings.gpuApiMode,
                    videoDecoderMode = appSettings.videoDecoderMode,
                    mpvProfileMode = appSettings.mpvProfileMode,
                    controlsAutoHideMillis = appSettings.videoControlsAutoHideMillis,
                    playerOrientationMode = appSettings.videoPlayerOrientationMode,
                    proxyDebugInfoEnabled = appSettings.videoPlayerProxyDebugInfoEnabled,
                    videoBackgroundMode = appSettings.videoBackgroundMode,
                    anime4kEnabled = appSettings.anime4kEnabled,
                    anime4kMode = appSettings.anime4kMode,
                    anime4kQuality = appSettings.anime4kQuality,
                ),
            )
        }.onFailure { error ->
            ReaderDiagnosticLog.error("play_video_download_failed uri=${record.localUri}", error)
            localOpenError = error.message ?: "无法播放该视频，文件可能已被删除"
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
        downloadProgress = null
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
        downloadProgress = null
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
        selectedDirectoryComic = item
        selectedWebDavFile = null
        selectedDirectoryVideo = null
        selectedLibraryItem = null
        selectedVideoLibraryItem = null
    }

    fun selectDirectoryVideoItem(item: FileDirectoryBrowserItem) {
        selectedDirectoryVideo = item
        selectedWebDavFile = null
        selectedDirectoryComic = null
        selectedLibraryItem = null
        selectedVideoLibraryItem = null
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

    val hasActiveSelection = hasActiveAppSelection(
        webDavFileSelected = selectedWebDavFile != null,
        directoryComicSelected = selectedDirectoryComic != null,
        directoryVideoSelected = selectedDirectoryVideo != null,
        libraryItemSelected = selectedLibraryItem != null,
        videoLibraryItemSelected = selectedVideoLibraryItem != null,
    )

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
                        onChooseFolder = { dataFolderPicker.launch(null) },
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
                                        logFolderPicker.launch(null)
                                    }
                                },
                                onCancelLoading = {
                                    ReaderDiagnosticLog.event("reader_open_cancel")
                                    val shouldRestoreMainPortrait = readerLandscapeModeState.value
                                    readerViewModel.closeReader()
                                    downloadProgress = null
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
                                    downloadProgress = null
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
                                    favoriteWebDavVideo(item)
                                },
                                onAddDirectoryComicToLibrary = { item ->
                                    clearSelection()
                                    favoriteLocalDirectoryComic(item)
                                },
                                onAddDirectoryVideoToVideoLibrary = { item ->
                                    clearSelection()
                                    favoriteLocalDirectoryVideo(item)
                                },
                                onRemoveLibraryItem = ::removeLibraryItem,
                                onRefreshLibraryCover = ::refreshLibraryCover,
                                onDownloadLibraryItem = { item ->
                                    selectedLibraryItem = null
                                    downloadLibraryWebDavComic(item)
                                },
                                onRemoveVideoLibraryItem = ::removeVideoLibraryItem,
                                onRefreshVideoLibraryThumbnail = ::refreshVideoLibraryThumbnail,
                                onDeleteVideoLibraryThumbnail = ::deleteVideoLibraryThumbnail,
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
                                                        MediaKind.Video -> openWebDavVideo(item)
                                                        MediaKind.Audio,
                                                        MediaKind.Subtitle,
                                                        MediaKind.Unknown,
                                                        -> Unit
                                                    }
                                                },
                                                onAddToLibrary = ::favoriteWebDavComic,
                                                onDownloadToLocal = ::downloadWebDavComicToLocal,
                                                onSelectFile = { item ->
                                                    selectedWebDavFile = item
                                                    selectedDirectoryComic = null
                                                    selectedDirectoryVideo = null
                                                    selectedLibraryItem = null
                                                    selectedVideoLibraryItem = null
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
                                                onAddLocalDirectory = { localDirectoryPicker.launch(null) },
                                                onOpenWebDav = ::startAddingWebDavSource,
                                                onOpenLibrary = ::openLibraryTabFromSources,
                                                onOpenSource = ::openFileDirectorySource,
                                                onOpenDirectory = fileDirectoryViewModel::openLocalDirectory,
                                                onOpenComic = ::openLocalDirectoryComic,
                                                onOpenVideo = ::openLocalDirectoryVideo,
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
                                            onAddLocalDirectory = { localDirectoryPicker.launch(null) },
                                            onOpenWebDav = ::startAddingWebDavSource,
                                            onOpenLibrary = ::openLibraryTabFromSources,
                                            onOpenSource = ::openFileDirectorySource,
                                            onOpenDirectory = fileDirectoryViewModel::openLocalDirectory,
                                            onOpenComic = ::openLocalDirectoryComic,
                                            onOpenVideo = ::openLocalDirectoryVideo,
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
                                            selectedLibraryItem = item
                                            selectedWebDavFile = null
                                            selectedDirectoryComic = null
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
                                        onOpenItem = ::openVideoLibraryItem,
                                        onSelectItem = { item ->
                                            selectedVideoLibraryItem = item
                                            selectedWebDavFile = null
                                            selectedDirectoryComic = null
                                            selectedDirectoryVideo = null
                                            selectedLibraryItem = null
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
                                        onPlayVideoDownload = ::playVideoDownloadRecord,
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

private fun buildLocalDirectoryEpisodeQueue(
    entries: List<FileDirectoryBrowserItem>,
    currentItem: FileDirectoryBrowserItem,
): VideoEpisodeQueue? {
    val videos = entries.filter { it.mediaKind == MediaKind.Video }
    val subtitles = entries.filter { it.mediaKind == MediaKind.Subtitle }
    val currentIndex = videos.indexOfFirst { it.uri == currentItem.uri }
    if (currentIndex < 0) return null
    val episodes = videos.map { video ->
        VideoEpisode.local(localVideoEpisodeRequest(video, subtitles))
    }
    return VideoEpisodeQueue(episodes = episodes, currentIndex = currentIndex)
}

private fun buildWebDavDirectoryEpisodeQueue(
    accountId: String,
    items: List<WebDavItem>,
    currentItem: WebDavItem,
): VideoEpisodeQueue? {
    val videos = items.filter { item ->
        mediaKindFor(name = item.name, isDirectory = item.isDirectory) == MediaKind.Video
    }
    val subtitles = items.filter { item ->
        mediaKindFor(name = item.name, isDirectory = item.isDirectory) == MediaKind.Subtitle
    }
    val currentIndex = videos.indexOfFirst { it.path == currentItem.path }
    if (currentIndex < 0) return null
    val episodes = videos.map { video ->
        VideoEpisode.webDav(webDavVideoEpisodeRequest(accountId, video, subtitles))
    }
    return VideoEpisodeQueue(episodes = episodes, currentIndex = currentIndex)
}

private fun localVideoEpisodeRequest(
    video: FileDirectoryBrowserItem,
    directoryEntries: List<FileDirectoryBrowserItem>,
): LocalVideoOpenRequest =
    LocalVideoOpenRequest(
        uri = video.uri,
        displayName = video.name,
        size = video.size,
        lastModified = video.lastModified,
        subtitles = findSidecarSubtitles(
            videoFileName = video.name,
            candidates = directoryEntries,
            nameOf = FileDirectoryBrowserItem::name,
            isDirectoryOf = FileDirectoryBrowserItem::isDirectory,
        ).map { subtitle ->
            VideoSubtitleOpenRequest(
                uri = subtitle.uri,
                displayName = subtitle.name,
            )
        },
    )

private fun webDavVideoEpisodeRequest(
    accountId: String,
    video: WebDavItem,
    directoryItems: List<WebDavItem>,
): WebDavVideoOpenRequest =
    WebDavVideoOpenRequest(
        accountId = accountId,
        remotePath = video.path,
        displayName = video.name,
        size = video.size,
        etag = video.etag,
        lastModified = video.lastModified,
        mimeType = mimeTypeForMediaFileName(video.name),
        subtitles = findSidecarSubtitles(
            videoFileName = video.name,
            candidates = directoryItems,
            nameOf = WebDavItem::name,
            isDirectoryOf = WebDavItem::isDirectory,
        ).map { subtitle ->
            WebDavSubtitleOpenRequest(
                remotePath = subtitle.path,
                displayName = subtitle.name,
                size = subtitle.size,
                etag = subtitle.etag,
                lastModified = subtitle.lastModified,
                mimeType = mimeTypeForMediaFileName(subtitle.name),
            )
        },
    )
