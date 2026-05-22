package com.example.comicdav

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.provider.DocumentsContract
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.datastore.preferences.preferencesDataStore
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Text
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Book
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Source
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.automirrored.filled.LibraryBooks
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.comicdav.data.AppDataFolderStore
import com.example.comicdav.data.AppSettings
import com.example.comicdav.data.AppSettingsStore
import com.example.comicdav.data.ComicCacheAnalysis
import com.example.comicdav.data.ComicCacheCategory
import com.example.comicdav.data.ComicCacheKey
import com.example.comicdav.data.ComicDownloadCache
import com.example.comicdav.data.DownloadRecord
import com.example.comicdav.data.DownloadRecordStore
import com.example.comicdav.data.VideoDownloadRecord
import com.example.comicdav.data.VideoDownloadStore
import com.example.comicdav.data.filedirectory.FileDirectoryRepository
import com.example.comicdav.data.filedirectory.FileDirectorySourceType
import com.example.comicdav.data.ReadingProgressStore
import com.example.comicdav.data.ReaderLoggingMode
import com.example.comicdav.data.SavedWebDavAccount
import com.example.comicdav.data.WebDavAccountStore
import com.example.comicdav.data.analyzeComicCache
import com.example.comicdav.data.clearComicCacheCategory
import com.example.comicdav.data.formatCacheSize
import com.example.comicdav.feature.filedirectory.AndroidLocalDirectoryReader
import com.example.comicdav.feature.filedirectory.FileDirectoryBrowserItem
import com.example.comicdav.feature.filedirectory.FileDirectoryScreen
import com.example.comicdav.data.filedirectory.FileDirectorySourceEntity
import com.example.comicdav.feature.filedirectory.FileDirectoryViewModel
import com.example.comicdav.data.library.LibraryItemWithSources
import com.example.comicdav.data.library.LibraryRepository
import com.example.comicdav.data.library.SourceType
import com.example.comicdav.data.library.createLibraryDatabase
import com.example.comicdav.data.videolibrary.VideoLibraryItemWithSources
import com.example.comicdav.data.videolibrary.VideoLibraryRepository
import com.example.comicdav.data.videolibrary.VideoSourceType
import com.example.comicdav.feature.library.LibraryScreen
import com.example.comicdav.feature.library.LibraryViewModel
import com.example.comicdav.feature.library.WebDavLibraryCoverExtractor
import com.example.comicdav.feature.reader.ReaderDiagnosticLog
import com.example.comicdav.feature.reader.ReaderLoadingProgress
import com.example.comicdav.feature.reader.ReaderScreen
import com.example.comicdav.feature.reader.ReaderViewModel
import com.example.comicdav.feature.reader.LocalComicOpener
import com.example.comicdav.feature.reader.OpenComicUseCase
import com.example.comicdav.feature.reader.ReaderPageCache
import com.example.comicdav.feature.reader.createReaderLogFile
import com.example.comicdav.feature.reader.localComicCacheKey
import com.example.comicdav.feature.settings.SettingsScreen
import com.example.comicdav.feature.settings.pageCacheLimitBytesForMb
import com.example.comicdav.feature.videolibrary.VideoLibraryScreen
import com.example.comicdav.feature.videolibrary.VideoLibraryViewModel
import com.example.comicdav.feature.videolibrary.VideoThumbnailExtractor
import com.example.comicdav.feature.webdav.DownloadProgressUi
import com.example.comicdav.feature.webdav.WEB_DAV_STATUS_CONNECTED
import com.example.comicdav.feature.webdav.WebDavAccountScreen
import com.example.comicdav.feature.webdav.WebDavBrowserScreen
import com.example.comicdav.feature.webdav.WebDavViewModel
import com.example.comicdav.network.RemoteFileInfo
import com.example.comicdav.network.WebDavClient
import com.example.comicdav.network.WebDavItem
import com.example.comicdav.ui.ComicDavCopy
import com.example.comicdav.ui.ComicDavTheme
import com.example.comicdav.video.LocalVideoOpenRequest
import com.example.comicdav.video.MediaKind
import com.example.comicdav.video.VideoSubtitleOpenRequest
import com.example.comicdav.video.WebDavSubtitleOpenRequest
import com.example.comicdav.video.WebDavVideoOpenRequest
import com.example.comicdav.video.findSidecarSubtitles
import com.example.comicdav.video.mediaKindFor
import com.example.comicdav.video.mimeTypeForMediaFileName
import com.example.comicdav.video.player.VideoPlayerActivity
import com.example.comicdav.video.proxy.VideoProxyManager
import com.example.comicdav.video.proxy.VideoProxySettings
import com.example.comicdav.video.proxy.startWebDavVideoPlayback
import com.example.comicdav.webdav.decodeWebDavPathForDisplay
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val Context.readingProgressDataStore by preferencesDataStore(name = "reading_progress")
private val Context.appDataFolderDataStore by preferencesDataStore(name = "app_data_folder")
private val Context.appSettingsDataStore by preferencesDataStore(name = "app_settings")
private val Context.webDavAccountDataStore by preferencesDataStore(name = "webdav_accounts")
private val Context.downloadRecordsDataStore by preferencesDataStore(name = "download_records")
private val Context.videoDownloadRecordsDataStore by preferencesDataStore(name = "video_download_records")

class MainActivity : ComponentActivity() {
    private var previousCrashHandler: Thread.UncaughtExceptionHandler? = null
    private var readerCrashHandler: Thread.UncaughtExceptionHandler? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
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
    val libraryDatabase = remember(context) {
        createLibraryDatabase(context)
    }
    val libraryRepository = remember(libraryDatabase) {
        LibraryRepository(libraryDatabase.libraryDao())
    }
    val videoLibraryRepository = remember(libraryDatabase) {
        VideoLibraryRepository(libraryDatabase.videoLibraryDao())
    }
    val fileDirectoryRepository = remember(libraryDatabase) {
        FileDirectoryRepository(libraryDatabase.fileDirectoryDao())
    }
    val localDirectoryReader = remember(context) {
        AndroidLocalDirectoryReader(context.applicationContext)
    }
    val localComicOpener = remember(context) {
        LocalComicOpener(context.applicationContext)
    }
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
                return FileDirectoryViewModel(fileDirectoryRepository, localDirectoryReader) as T
            }
        },
    )
    val uiState = webDavViewModel.uiState
    val readerUiState = readerViewModel.uiState
    val libraryUiState = libraryViewModel.uiState
    val videoLibraryUiState = videoLibraryViewModel.uiState
    val fileDirectoryUiState = fileDirectoryViewModel.uiState
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    var isReaderOpen by rememberSaveable { mutableStateOf(false) }
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
    var selectedDownloadRecord by remember { mutableStateOf<DownloadRecord?>(null) }
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
    val remoteCache = remember(context) { ComicDownloadCache(File(context.cacheDir, "remote-comics")) }
    val coverExtractor = remember(context, remoteCache) {
        WebDavLibraryCoverExtractor(
            appCacheDir = context.cacheDir,
            remoteCacheDir = remoteCache.cacheDir,
        )
    }
    val videoThumbnailExtractor = remember(context) {
        VideoThumbnailExtractor(cacheDir = context.cacheDir)
    }
    val progressStore = remember(context) { ReadingProgressStore(context.readingProgressDataStore) }
    val dataFolderStore = remember(context) { AppDataFolderStore(context.appDataFolderDataStore) }
    val appSettingsStore = remember(context) { AppSettingsStore(context.appSettingsDataStore) }
    val webDavAccountStore = remember(context) { WebDavAccountStore(context.webDavAccountDataStore) }
    val downloadRecordStore = remember(context) { DownloadRecordStore(context.downloadRecordsDataStore) }
    val videoDownloadStore = remember(context) { VideoDownloadStore(context.videoDownloadRecordsDataStore) }
    val appSettings by appSettingsStore.settings.collectAsState(initial = AppSettings(videoResumeEnabled = false))
    val downloadRecords by downloadRecordStore.records.collectAsState(initial = emptyList())
    fun clearSelection() {
        selectedWebDavFile = null
        selectedDirectoryComic = null
        selectedDirectoryVideo = null
        selectedLibraryItem = null
        selectedVideoLibraryItem = null
        selectedDownloadRecord = null
    }
    fun refreshCacheAnalysis() {
        scope.launch {
            cacheAnalysis = withContext(Dispatchers.IO) {
                analyzeComicCache(context.cacheDir)
            }
        }
    }
    LaunchedEffect(context.cacheDir) {
        cacheAnalysis = withContext(Dispatchers.IO) {
            analyzeComicCache(context.cacheDir)
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

    LaunchedEffect(appSettings.screenRotationLockEnabled) {
        (context as? Activity)?.requestedOrientation =
            mainAppRequestedOrientation(appSettings.screenRotationLockEnabled)
    }

    DisposableEffect(context, lifecycleOwner, appSettings.screenRotationLockEnabled) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                (context as? Activity)?.requestedOrientation =
                    mainAppRequestedOrientation(appSettings.screenRotationLockEnabled)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    LaunchedEffect(appSettings.readerLoggingMode) {
        ReaderDiagnosticLog.setMode(appSettings.readerLoggingMode)
        if (!appSettings.loggingEnabled) {
            ReaderDiagnosticLog.clearSink()
        }
    }

    LaunchedEffect(appSettings.diskCacheLimitMb) {
        val pageCacheLimitBytes = pageCacheLimitBytesForMb(appSettings.diskCacheLimitMb)
        readerViewModel.updatePageCacheMaxBytes(pageCacheLimitBytes)
        withContext(Dispatchers.IO) {
            ReaderPageCache.prune(context.cacheDir, maxBytes = pageCacheLimitBytes)
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
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    localComicOpener.open(uri, fileName)
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
                    )
                    isReaderOpen = true
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
        val request = LocalVideoOpenRequest(
            uri = item.uri,
            displayName = item.name,
            size = item.size,
            lastModified = item.lastModified,
            subtitles = findSidecarSubtitles(
                videoFileName = item.name,
                candidates = fileDirectoryUiState.entries,
                nameOf = FileDirectoryBrowserItem::name,
                isDirectoryOf = FileDirectoryBrowserItem::isDirectory,
            ).map { subtitle ->
                VideoSubtitleOpenRequest(
                    uri = subtitle.uri,
                    displayName = subtitle.name,
                )
            },
        )
        pendingLocalVideoOpen = request
        localOpenError = null
        context.startActivity(
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
        if (client == null) {
            localOpenError = "请先连接 WebDAV，再下载漫画"
            webDavActionMessage = null
            return
        }
        downloadProgress = null
        localOpenError = null
        webDavActionMessage = null
        scope.launch {
            runCatching {
                val info = item.size?.let { knownSize ->
                    RemoteFileInfo(
                        path = item.path,
                        size = knownSize,
                        etag = item.etag,
                        lastModified = item.lastModified,
                        supportsRange = true,
                    )
                } ?: client.head(item.path)
                val key = ComicCacheKey.fromRemote(
                    accountId = accountId,
                    remotePath = item.path,
                    size = info.size,
                    etag = info.etag,
                    lastModified = info.lastModified,
                )
                remoteCache.download(
                    client = client,
                    remotePath = item.path,
                    key = key,
                    expectedSize = info.size,
                ) { downloaded, total ->
                    scope.launch {
                        downloadProgress = DownloadProgressUi(downloaded, total)
                    }
                }
                info.size
            }.fold(
                onSuccess = { sizeBytes ->
                    downloadRecordStore.addRecord(
                        DownloadRecord(
                            fileName = item.name,
                            remotePath = item.path,
                            sizeBytes = sizeBytes,
                            downloadedAtMillis = System.currentTimeMillis(),
                            accountId = accountId,
                        ),
                    )
                    refreshCacheAnalysis()
                    downloadProgress = null
                    webDavActionMessage = "已下载 ${item.name} 到本地"
                    fileDirectoryViewModel.showMessage("已下载 ${item.name} 到本地")
                },
                onFailure = { error ->
                    downloadProgress = null
                    localOpenError = error.message ?: "下载到本地失败"
                    ReaderDiagnosticLog.error("download_webdav_comic_failed path=${item.path}", error)
                    fileDirectoryViewModel.showError(error.message ?: "下载到本地失败")
                },
            )
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
                    remotePath = item.path,
                    fileName = item.name,
                    expectedSize = info.size,
                ) { downloaded, total ->
                    if (progressThrottler.shouldReport(downloaded, total)) {
                        scope.launch {
                            downloadProgress = DownloadProgressUi(downloaded, total)
                        }
                    }
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
                webDavActionMessage = "已取消下载"
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
        val request = WebDavVideoOpenRequest(
            accountId = accountId,
            remotePath = item.path,
            displayName = item.name,
            size = item.size,
            etag = item.etag,
            lastModified = item.lastModified,
            mimeType = mimeTypeForMediaFileName(item.name),
            subtitles = findSidecarSubtitles(
                videoFileName = item.name,
                candidates = uiState.items,
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
                    context.startActivity(
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
        downloadProgress = null
        localOpenError = null
        webDavActionMessage = null
        scope.launch {
            runCatching {
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
                val key = ComicCacheKey.fromRemote(
                    accountId = accountId,
                    remotePath = remotePath,
                    size = info.size,
                    etag = info.etag,
                    lastModified = info.lastModified,
                )
                remoteCache.download(
                    client = client,
                    remotePath = remotePath,
                    key = key,
                    expectedSize = info.size,
                ) { downloaded, total ->
                    scope.launch {
                        downloadProgress = DownloadProgressUi(downloaded, total)
                    }
                }
                info.size
            }.fold(
                onSuccess = { sizeBytes ->
                    downloadRecordStore.addRecord(
                        DownloadRecord(
                            fileName = fileName,
                            remotePath = remotePath,
                            sizeBytes = sizeBytes,
                            downloadedAtMillis = System.currentTimeMillis(),
                            accountId = accountId,
                        ),
                    )
                    refreshCacheAnalysis()
                    downloadProgress = null
                    onSuccess("已下载 $fileName 到本地")
                },
                onFailure = { error ->
                    downloadProgress = null
                    ReaderDiagnosticLog.error("download_remote_comic_failed path=$remotePath", error)
                    onFailure(error.message ?: "下载到本地失败")
                },
            )
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
                    context.startActivity(
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
                            context.startActivity(
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
                    selectedDownloadRecord = null
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
            selectedDownloadRecord = null
        }
    }

    fun closeReaderFromNavigation() {
        ReaderDiagnosticLog.event("reader_navigation_close")
        readerViewModel.closeReader()
        downloadProgress = null
        webDavActionMessage = null
        isReaderOpen = false
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
        isReaderOpen = true
        startReaderLogFile(context, logFolderUriText, scope, appSettings.readerLoggingMode != ReaderLoggingMode.OFF)
        ReaderDiagnosticLog.event("open_remote_start path=$remotePath size=${size ?: -1}")
        readerViewModel.openRemote(cacheDir = context.cacheDir) {
            val useCase = OpenComicUseCase(
                accountId = accountId,
                cache = remoteCache,
                progressStore = progressStore,
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

    BackHandler(
        enabled = selectedWebDavFile != null ||
            selectedDirectoryComic != null ||
            selectedDirectoryVideo != null ||
            selectedLibraryItem != null ||
            selectedVideoLibraryItem != null ||
            selectedDownloadRecord != null ||
            isReaderOpen ||
            isWebDavOpen ||
            fileDirectoryUiState.currentTitle != null ||
            selectedTab != AppTab.SOURCES,
    ) {
        when {
            selectedWebDavFile != null ||
                selectedDirectoryComic != null ||
                selectedDirectoryVideo != null ||
                selectedLibraryItem != null ||
                selectedVideoLibraryItem != null ||
                selectedDownloadRecord != null -> clearSelection()
            isReaderOpen -> closeReaderFromNavigation()
            isWebDavOpen -> {
                if (!webDavViewModel.handleBack()) {
                    isWebDavOpen = false
                    isAddingWebDavPath = false
                    editingWebDavSourceId = null
                    localOpenError = null
                    webDavActionMessage = null
                }
            }
            selectedTab == AppTab.SOURCES && fileDirectoryViewModel.handleBack() -> Unit
            selectedTab != AppTab.SOURCES -> {
                selectedTabName = AppTab.SOURCES.name
                localOpenError = null
                webDavActionMessage = null
            }
        }
    }

    LaunchedEffect(selectedTab) {
        if (selectedTab == AppTab.SETTINGS) {
            cacheAnalysis = withContext(Dispatchers.IO) {
                analyzeComicCache(context.cacheDir)
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

                isReaderOpen -> {
                    ReaderScreen(
                        uiState = readerUiState.copy(error = readerUiState.error ?: localOpenError),
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
                        loadingProgress = downloadProgress?.toReaderLoadingProgress(),
                        onCancelLoading = {
                            ReaderDiagnosticLog.event("reader_open_cancel")
                            readerViewModel.closeReader()
                            downloadProgress = null
                            isReaderOpen = false
                        },
                        onClose = {
                            ReaderDiagnosticLog.event("reader_close")
                            readerViewModel.closeReader()
                            downloadProgress = null
                            isReaderOpen = false
                        },
                        readingDirection = appSettings.readingDirection,
                        autoPageEnabled = appSettings.autoPageEnabled,
                        onAutoPageEnabledChange = { value ->
                            scope.launch { appSettingsStore.updateAutoPageEnabled(value) }
                        },
                        autoPageIntervalMillis = appSettings.autoPageSpeedMillis.toLong(),
                        volumeKeysTurnPages = appSettings.volumeKeysTurnPagesEnabled,
                    )
                }

                else -> {
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
                            selectedDownloadRecord = selectedDownloadRecord,
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
                            onDeleteDownloadRecord = ::deleteDownloadRecord,
                            onAddDownloadRecordToLibrary = ::addDownloadRecordToLibrary,
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
                                                selectedDownloadRecord = null
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
                                            onBackToDirectories = {
                                                isWebDavOpen = false
                                                isAddingWebDavPath = false
                                                editingWebDavSourceId = null
                                                localOpenError = null
                                                webDavActionMessage = null
                                            },
                                            showSaveDirectoryAction = isAddingWebDavPath,
                                            downloadProgress = downloadProgress,
                                            downloadError = localOpenError,
                                            actionMessage = webDavActionMessage,
                                            onCancelDownload = {
                                                activeDownloadJob?.cancel()
                                                activeDownloadJob = null
                                                downloadProgress = null
                                                localOpenError = null
                                                webDavActionMessage = "已取消下载"
                                            },
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
                                        FileDirectoryScreen(
                                            uiState = fileDirectoryUiState.copy(error = fileDirectoryUiState.error ?: localOpenError ?: uiState.message.takeIf { it.isNotBlank() }),
                                            onAddLocalDirectory = {
                                                localDirectoryPicker.launch(null)
                                            },
                                            onOpenWebDav = {
                                                localOpenError = null
                                                webDavActionMessage = null
                                                editingWebDavSourceId = null
                                                webDavViewModel.startNewConnection()
                                                isWebDavOpen = true
                                                isAddingWebDavPath = true
                                            },
                                            onOpenLibrary = {
                                                localOpenError = null
                                                webDavActionMessage = null
                                                selectedTabName = AppTab.LIBRARY.name
                                            },
                                            onOpenSource = { source ->
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
                                            },
                                            onOpenDirectory = fileDirectoryViewModel::openLocalDirectory,
                                            onOpenComic = ::openLocalDirectoryComic,
                                            onOpenVideo = ::openLocalDirectoryVideo,
                                            onSelectComic = { item ->
                                                selectedDirectoryComic = item
                                                selectedWebDavFile = null
                                                selectedDirectoryVideo = null
                                                selectedLibraryItem = null
                                                selectedVideoLibraryItem = null
                                                selectedDownloadRecord = null
                                            },
                                            onSelectVideo = { item ->
                                                selectedDirectoryVideo = item
                                                selectedWebDavFile = null
                                                selectedDirectoryComic = null
                                                selectedLibraryItem = null
                                                selectedVideoLibraryItem = null
                                                selectedDownloadRecord = null
                                            },
                                            onGoUp = fileDirectoryViewModel::goUp,
                                            onCloseBrowser = fileDirectoryViewModel::closeLocalBrowser,
                                            onDismissMessage = {
                                                localOpenError = null
                                                fileDirectoryViewModel.clearMessage()
                                            },
                                            onDeleteSource = { source ->
                                                fileDirectoryViewModel.deleteSource(source.id)
                                            },
                                            onDeleteLocalSourceWithFiles = ::deleteLocalSourceWithFiles,
                                            onEditWebDavSource = { source ->
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
                                            },
                                            selectedComic = selectedDirectoryComic,
                                            selectedVideo = selectedDirectoryVideo,
                                            modifier = contentModifier,
                                        )
                                    }
                                } else {
                                    FileDirectoryScreen(
                                        uiState = fileDirectoryUiState.copy(error = fileDirectoryUiState.error ?: localOpenError),
                                        onAddLocalDirectory = {
                                            localDirectoryPicker.launch(null)
                                        },
                                        onOpenWebDav = {
                                            localOpenError = null
                                            webDavActionMessage = null
                                            editingWebDavSourceId = null
                                            webDavViewModel.startNewConnection()
                                            isWebDavOpen = true
                                            isAddingWebDavPath = true
                                        },
                                        onOpenLibrary = {
                                            localOpenError = null
                                            webDavActionMessage = null
                                            selectedTabName = AppTab.LIBRARY.name
                                        },
                                        onOpenSource = { source ->
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
                                        },
                                        onOpenDirectory = fileDirectoryViewModel::openLocalDirectory,
                                        onOpenComic = ::openLocalDirectoryComic,
                                        onOpenVideo = ::openLocalDirectoryVideo,
                                        onSelectComic = { item ->
                                            selectedDirectoryComic = item
                                            selectedWebDavFile = null
                                            selectedDirectoryVideo = null
                                            selectedLibraryItem = null
                                            selectedVideoLibraryItem = null
                                            selectedDownloadRecord = null
                                        },
                                        onSelectVideo = { item ->
                                            selectedDirectoryVideo = item
                                            selectedWebDavFile = null
                                            selectedDirectoryComic = null
                                            selectedLibraryItem = null
                                            selectedVideoLibraryItem = null
                                            selectedDownloadRecord = null
                                        },
                                        onGoUp = fileDirectoryViewModel::goUp,
                                        onCloseBrowser = fileDirectoryViewModel::closeLocalBrowser,
                                        onDismissMessage = {
                                            localOpenError = null
                                            fileDirectoryViewModel.clearMessage()
                                        },
                                        onDeleteSource = { source ->
                                            fileDirectoryViewModel.deleteSource(source.id)
                                        },
                                        onDeleteLocalSourceWithFiles = ::deleteLocalSourceWithFiles,
                                        onEditWebDavSource = { source ->
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
                                        },
                                        selectedComic = selectedDirectoryComic,
                                        selectedVideo = selectedDirectoryVideo,
                                        modifier = contentModifier,
                                    )
                                }
                            }
                            AppTab.LIBRARY -> {
                                LibraryScreen(
                                    uiState = libraryUiState.copy(error = libraryUiState.error ?: localOpenError),
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
                                        selectedDownloadRecord = null
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
                                VideoLibraryScreen(
                                    uiState = videoLibraryUiState.copy(error = videoLibraryUiState.error ?: localOpenError),
                                    onOpenItem = ::openVideoLibraryItem,
                                    onSelectItem = { item ->
                                        selectedVideoLibraryItem = item
                                        selectedWebDavFile = null
                                        selectedDirectoryComic = null
                                        selectedDirectoryVideo = null
                                        selectedLibraryItem = null
                                        selectedDownloadRecord = null
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
                            AppTab.SETTINGS -> {
                                SettingsScreen(
                                    settings = appSettings,
                                    onReadingDirectionChange = { value ->
                                        scope.launch { appSettingsStore.updateReadingDirection(value) }
                                    },
                                    onReaderLoggingModeChange = { value ->
                                        scope.launch { appSettingsStore.updateReaderLoggingMode(value) }
                                    },
                                    onColorPaletteChange = { value ->
                                        scope.launch { appSettingsStore.updateColorPalette(value) }
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
                                    downloadRecords = downloadRecords,
                                    selectedDownloadRecord = selectedDownloadRecord,
                                    onSelectDownloadRecord = { record ->
                                        selectedDownloadRecord = record
                                        selectedWebDavFile = null
                                        selectedDirectoryComic = null
                                        selectedDirectoryVideo = null
                                        selectedLibraryItem = null
                                        selectedVideoLibraryItem = null
                                    },
                                    onClearSelectedDownloadRecord = {
                                        selectedDownloadRecord = null
                                    },
                                    cacheAnalysis = cacheAnalysis,
                                    cacheActionMessage = cacheActionMessage,
                                    onClearCacheCategory = { category ->
                                        scope.launch {
                                            val result = withContext(Dispatchers.IO) {
                                                clearComicCacheCategory(context.cacheDir, category)
                                            }
                                            cacheAnalysis = withContext(Dispatchers.IO) {
                                                analyzeComicCache(context.cacheDir)
                                            }
                                            cacheActionMessage =
                                                "已清理 ${category.cacheLabel()}：${result.filesDeleted} 个文件，释放 ${formatCacheSize(result.bytesDeleted)}"
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

private fun ComicCacheCategory.cacheLabel(): String =
    when (this) {
        ComicCacheCategory.REMOTE_DOWNLOADS -> "远程整本缓存"
        ComicCacheCategory.REMOTE_INDEX -> "WebDAV 索引缓存"
        ComicCacheCategory.READER_PAGES -> "页面图片缓存"
        ComicCacheCategory.LIBRARY_COVERS -> "书架封面缓存"
    }

internal fun shouldShowWebDavAccountForm(
    isAddingWebDavPath: Boolean,
    editingWebDavSourceId: Long?,
    webDavStatus: String,
): Boolean =
    webDavStatus != WEB_DAV_STATUS_CONNECTED && (isAddingWebDavPath || editingWebDavSourceId != null)

internal fun mainAppRequestedOrientation(screenRotationLockEnabled: Boolean): Int =
    if (screenRotationLockEnabled) {
        ActivityInfo.SCREEN_ORIENTATION_LOCKED
    } else {
        ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
    }

internal class DownloadProgressThrottler(
    private val minIntervalMillis: Long = 250L,
    private val minByteDelta: Long = 512L * 1024L,
) {
    private var lastReportMillis: Long? = null
    private var lastReportedBytes: Long = 0L

    fun shouldReport(
        downloadedBytes: Long,
        totalBytes: Long,
        nowMillis: Long = SystemClock.elapsedRealtime(),
    ): Boolean {
        val lastMillis = lastReportMillis
        val isComplete = totalBytes > 0L && downloadedBytes >= totalBytes
        val shouldReport = lastMillis == null ||
            nowMillis - lastMillis >= minIntervalMillis ||
            downloadedBytes - lastReportedBytes >= minByteDelta ||
            isComplete
        if (shouldReport) {
            lastReportMillis = nowMillis
            lastReportedBytes = downloadedBytes
        }
        return shouldReport
    }
}

internal fun parentWebDavDirectoryPath(remotePath: String): String {
    val normalized = remotePath.takeIf { it.isNotBlank() } ?: return "/"
    val withoutTrailingSlash = normalized.trimEnd('/')
    val slashIndex = withoutTrailingSlash.lastIndexOf('/')
    return if (slashIndex <= 0) {
        "/"
    } else {
        withoutTrailingSlash.substring(0, slashIndex + 1)
    }
}

private fun parentDocumentUriForLocalVideo(videoUri: Uri): Uri? {
    return runCatching {
        val documentId = DocumentsContract.getDocumentId(videoUri)
        val parentDocumentId = documentId.substringBeforeLast('/', missingDelimiterValue = "")
        if (parentDocumentId.isBlank()) {
            null
        } else {
            DocumentsContract.buildDocumentUriUsingTree(videoUri, parentDocumentId)
        }
    }.getOrNull()
}

internal fun appTabLabels(): List<String> =
    AppTab.values().map { it.label }

internal fun selectionActionLabelsForLocalVideo(): List<String> =
    listOf("加入影视库", "取消")

internal fun selectionActionLabelsForWebDavVideo(): List<String> =
    listOf("加入影视库", "下载", "取消")

internal fun selectionActionLabelsForVideoLibraryItem(): List<String> =
    listOf("重新提取缩略图", "移除", "删除缩略图", "取消")

private enum class AppTab {
    SOURCES,
    LIBRARY,
    VIDEO_LIBRARY,
    SETTINGS;

    val label: String
        get() = when (this) {
            SOURCES -> ComicDavCopy.sourcesTab
            LIBRARY -> ComicDavCopy.libraryTab
            VIDEO_LIBRARY -> ComicDavCopy.videoLibraryTab
            SETTINGS -> ComicDavCopy.settingsTab
        }

    val iconVector: ImageVector
        get() = when (this) {
            SOURCES -> Icons.Filled.Folder
            LIBRARY -> Icons.AutoMirrored.Filled.LibraryBooks
            VIDEO_LIBRARY -> Icons.Filled.PlayArrow
            SETTINGS -> Icons.Filled.Settings
        }
}

@Composable
private fun ComicDavAppShell(
    selectedTab: AppTab,
    onTabSelected: (AppTab) -> Unit,
    modifier: Modifier = Modifier,
    bottomBar: (@Composable () -> Unit)? = null,
    content: @Composable (Modifier) -> Unit,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        Box(modifier = Modifier.weight(1f)) {
            content(Modifier.fillMaxSize())
        }
        if (bottomBar != null) {
            bottomBar()
        } else {
            NavigationBar(
                containerColor = MaterialTheme.colorScheme.surface,
                tonalElevation = 3.dp,
            ) {
                AppTab.values().forEach { tab ->
                    NavigationBarItem(
                        selected = selectedTab == tab,
                        onClick = { onTabSelected(tab) },
                        icon = {
                            Icon(
                                imageVector = tab.iconVector,
                                contentDescription = tab.label,
                            )
                        },
                        label = {
                            Text(
                                text = tab.label,
                                maxLines = 1,
                            )
                        },
                    )
                }
            }
        }
    }
}

private data class SelectionAction(
    val label: String,
    val icon: ImageVector,
    val enabled: Boolean = true,
    val onClick: () -> Unit,
)

private fun selectionBottomBar(
    selectedWebDavFile: WebDavItem?,
    selectedDirectoryComic: FileDirectoryBrowserItem?,
    selectedDirectoryVideo: FileDirectoryBrowserItem?,
    selectedLibraryItem: LibraryItemWithSources?,
    selectedVideoLibraryItem: VideoLibraryItemWithSources?,
    selectedDownloadRecord: DownloadRecord?,
    onDownloadWebDavFile: (WebDavItem) -> Unit,
    onDownloadWebDavVideo: (WebDavItem) -> Unit,
    onAddWebDavFileToLibrary: (WebDavItem) -> Unit,
    onAddWebDavVideoToVideoLibrary: (WebDavItem) -> Unit,
    onAddDirectoryComicToLibrary: (FileDirectoryBrowserItem) -> Unit,
    onAddDirectoryVideoToVideoLibrary: (FileDirectoryBrowserItem) -> Unit,
    onRemoveLibraryItem: (LibraryItemWithSources) -> Unit,
    onRefreshLibraryCover: (LibraryItemWithSources) -> Unit,
    onDownloadLibraryItem: (LibraryItemWithSources) -> Unit,
    onRemoveVideoLibraryItem: (VideoLibraryItemWithSources) -> Unit,
    onRefreshVideoLibraryThumbnail: (VideoLibraryItemWithSources) -> Unit,
    onDeleteVideoLibraryThumbnail: (VideoLibraryItemWithSources) -> Unit,
    onDeleteDownloadRecord: (DownloadRecord) -> Unit,
    onAddDownloadRecordToLibrary: (DownloadRecord) -> Unit,
    onCancel: () -> Unit,
): (@Composable () -> Unit)? {
    val actions = when {
        selectedWebDavFile != null -> when (mediaKindFor(name = selectedWebDavFile.name, isDirectory = selectedWebDavFile.isDirectory)) {
            MediaKind.Video -> listOf(
                SelectionAction("加入影视库", Icons.Filled.PlayArrow) { onAddWebDavVideoToVideoLibrary(selectedWebDavFile) },
                SelectionAction("下载", Icons.Filled.Download) { onDownloadWebDavVideo(selectedWebDavFile) },
                SelectionAction("取消", Icons.Filled.Close, onClick = onCancel),
            )
            else -> listOf(
                SelectionAction("下载", Icons.Filled.Download) { onDownloadWebDavFile(selectedWebDavFile) },
                SelectionAction("加入书架", Icons.Filled.Book) { onAddWebDavFileToLibrary(selectedWebDavFile) },
                SelectionAction("取消", Icons.Filled.Close, onClick = onCancel),
            )
        }
        selectedDirectoryComic != null -> listOf(
            SelectionAction("加入书架", Icons.Filled.Book) { onAddDirectoryComicToLibrary(selectedDirectoryComic) },
            SelectionAction("取消", Icons.Filled.Close, onClick = onCancel),
        )
        selectedDirectoryVideo != null -> listOf(
            SelectionAction("加入影视库", Icons.Filled.PlayArrow) { onAddDirectoryVideoToVideoLibrary(selectedDirectoryVideo) },
            SelectionAction("取消", Icons.Filled.Close, onClick = onCancel),
        )
        selectedLibraryItem != null -> {
            val isWebDav = selectedLibraryItem.webDavSource != null
            listOf(
                SelectionAction("移除", Icons.Filled.Delete) { onRemoveLibraryItem(selectedLibraryItem) },
                SelectionAction("重新获取封面", Icons.Filled.Refresh, enabled = isWebDav) {
                    onRefreshLibraryCover(selectedLibraryItem)
                },
                SelectionAction("下载", Icons.Filled.Download, enabled = isWebDav) {
                    onDownloadLibraryItem(selectedLibraryItem)
                },
                SelectionAction("取消", Icons.Filled.Close, onClick = onCancel),
            )
        }
        selectedVideoLibraryItem != null -> listOf(
            SelectionAction("重新提取缩略图", Icons.Filled.Refresh) {
                onRefreshVideoLibraryThumbnail(selectedVideoLibraryItem)
            },
            SelectionAction("移除", Icons.Filled.Delete) { onRemoveVideoLibraryItem(selectedVideoLibraryItem) },
            SelectionAction("删除缩略图", Icons.Filled.Delete) { onDeleteVideoLibraryThumbnail(selectedVideoLibraryItem) },
            SelectionAction("取消", Icons.Filled.Close, onClick = onCancel),
        )
        selectedDownloadRecord != null -> listOf(
            SelectionAction("删除", Icons.Filled.Delete) { onDeleteDownloadRecord(selectedDownloadRecord) },
            SelectionAction("取消", Icons.Filled.Close, onClick = onCancel),
            SelectionAction("加入书架", Icons.Filled.Book) { onAddDownloadRecordToLibrary(selectedDownloadRecord) },
        )
        else -> return null
    }
    return {
        SelectionNavigationBar(actions = actions)
    }
}

@Composable
private fun SelectionNavigationBar(actions: List<SelectionAction>) {
    NavigationBar(
        containerColor = MaterialTheme.colorScheme.surface,
        tonalElevation = 3.dp,
    ) {
        actions.forEach { action ->
            NavigationBarItem(
                selected = false,
                enabled = action.enabled,
                onClick = action.onClick,
                icon = {
                    Icon(
                        imageVector = action.icon,
                        contentDescription = action.label,
                    )
                },
                label = {
                    Text(
                        text = action.label,
                        maxLines = 1,
                    )
                },
            )
        }
    }
}

@Composable
private fun DataFolderGateScreen(
    onChooseFolder: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.padding(28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = ComicDavCopy.chooseDataFolderTitle,
            style = MaterialTheme.typography.headlineSmall,
        )
        Text(
            text = ComicDavCopy.chooseDataFolderBody,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 10.dp, bottom = 20.dp),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Button(onClick = onChooseFolder) {
            Text(ComicDavCopy.chooseFolder)
        }
    }
}

private fun queryDirectoryDisplayName(context: Context, treeUri: Uri): String {
    val rootDocumentUri = DocumentsContract.buildDocumentUriUsingTree(
        treeUri,
        DocumentsContract.getTreeDocumentId(treeUri),
    )
    context.contentResolver.query(
        rootDocumentUri,
        arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME),
        null,
        null,
        null,
    )?.use { cursor ->
        if (cursor.moveToFirst()) {
            val nameIndex = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
            if (nameIndex >= 0 && !cursor.isNull(nameIndex)) {
                return cursor.getString(nameIndex)
            }
        }
    }
    return treeUri.lastPathSegment
        ?.substringAfterLast(':')
        ?.let(::decodeWebDavPathForDisplay)
        ?.ifBlank { null }
        ?: "本地文件夹"
}

private fun deleteLocalSourceTree(context: Context, treeUri: Uri) {
    val rootDocumentUri = DocumentsContract.buildDocumentUriUsingTree(
        treeUri,
        DocumentsContract.getTreeDocumentId(treeUri),
    )
    val deleted = DocumentsContract.deleteDocument(context.contentResolver, rootDocumentUri)
    check(deleted) { "系统未允许删除这个本地文件夹" }
}

private suspend fun downloadWebDavVideoToDataFolder(
    context: Context,
    folderTreeUri: Uri,
    client: WebDavClient,
    remotePath: String,
    fileName: String,
    expectedSize: Long,
    onProgress: (downloadedBytes: Long, totalBytes: Long) -> Unit,
): String = withContext(Dispatchers.IO) {
    val resolver = context.applicationContext.contentResolver
    val rootDocumentUri = DocumentsContract.buildDocumentUriUsingTree(
        folderTreeUri,
        DocumentsContract.getTreeDocumentId(folderTreeUri),
    )
    val videosDirectoryUri = findOrCreateChildDocument(
        context = context,
        parentDocumentUri = rootDocumentUri,
        displayName = "videos",
        mimeType = DocumentsContract.Document.MIME_TYPE_DIR,
    )
    val safeName = sanitizeDownloadedVideoFileName(fileName)
    findChildDocumentUri(context, videosDirectoryUri, "$safeName.tmp")?.let { tmp ->
        DocumentsContract.deleteDocument(resolver, tmp)
    }
    val tmpUri = requireNotNull(
        DocumentsContract.createDocument(
            resolver,
            videosDirectoryUri,
            mimeTypeForMediaFileName(fileName) ?: "application/octet-stream",
            "$safeName.tmp",
        ),
    ) { "无法在数据文件夹创建视频临时文件" }

    try {
        val downloadJob = currentCoroutineContext().job
        val response = client.openFullStream(remotePath) { closeable ->
            downloadJob.invokeOnCompletion { cause ->
                if (cause is CancellationException) {
                    runCatching { closeable.close() }
                }
            }
        }
        var downloaded = 0L
        try {
            response.stream.use { input ->
                resolver.openOutputStream(tmpUri, "w").use { output ->
                    requireNotNull(output) { "无法写入视频文件" }
                    downloaded = copyStreamWithProgress(
                        input = input,
                        output = output,
                        totalBytes = expectedSize.takeIf { it > 0L }
                            ?: response.totalSize
                            ?: response.contentLength.takeIf { it > 0L }
                            ?: 0L,
                        onProgress = onProgress,
                    )
                }
            }
        } finally {
            response.close()
        }
        if (expectedSize > 0L && downloaded != expectedSize) {
            error("Downloaded $downloaded bytes, expected $expectedSize")
        }
        findChildDocumentUri(context, videosDirectoryUri, safeName)?.let { existing ->
            check(DocumentsContract.deleteDocument(resolver, existing)) { "无法替换已有视频文件" }
        }
        val finalUri = requireNotNull(
            DocumentsContract.renameDocument(resolver, tmpUri, safeName),
        ) { "无法保存视频文件" }
        finalUri.toString()
    } catch (error: Throwable) {
        runCatching { DocumentsContract.deleteDocument(resolver, tmpUri) }
        throw error
    }
}

private fun findOrCreateChildDocument(
    context: Context,
    parentDocumentUri: Uri,
    displayName: String,
    mimeType: String,
): Uri {
    findChildDocumentUri(context, parentDocumentUri, displayName)?.let { return it }
    return requireNotNull(
        DocumentsContract.createDocument(
            context.contentResolver,
            parentDocumentUri,
            mimeType,
            displayName,
        ),
    ) { "无法创建 $displayName 文件夹" }
}

private fun findChildDocumentUri(
    context: Context,
    parentDocumentUri: Uri,
    displayName: String,
): Uri? {
    val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(
        parentDocumentUri,
        DocumentsContract.getDocumentId(parentDocumentUri),
    )
    context.contentResolver.query(
        childrenUri,
        arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
        ),
        null,
        null,
        null,
    )?.use { cursor ->
        val idColumn = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
        val nameColumn = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
        while (cursor.moveToNext()) {
            if (idColumn < 0 || nameColumn < 0 || cursor.isNull(idColumn) || cursor.isNull(nameColumn)) continue
            if (cursor.getString(nameColumn) == displayName) {
                return DocumentsContract.buildDocumentUriUsingTree(
                    parentDocumentUri,
                    cursor.getString(idColumn),
                )
            }
        }
    }
    return null
}

private suspend fun copyStreamWithProgress(
    input: InputStream,
    output: OutputStream,
    totalBytes: Long,
    onProgress: (downloadedBytes: Long, totalBytes: Long) -> Unit,
): Long {
    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
    var downloaded = 0L
    while (true) {
        currentCoroutineContext().ensureActive()
        val read = input.read(buffer)
        if (read == -1) break
        output.write(buffer, 0, read)
        downloaded += read
        onProgress(downloaded, totalBytes)
    }
    output.flush()
    return downloaded
}

private fun sanitizeDownloadedVideoFileName(fileName: String): String {
    val sanitized = fileName
        .replace(Regex("""[\\/:*?"<>|\u0000-\u001F]"""), "_")
        .trim()
        .trim('.')
        .take(180)
    return sanitized.ifBlank { "video-download" }
}

private fun loadReaderLogFolderUri(context: Context): String? {
    return context
        .getSharedPreferences(READER_DIAGNOSTIC_PREFS, Context.MODE_PRIVATE)
        .getString(READER_LOG_FOLDER_URI_KEY, null)
}

private fun saveReaderLogFolderUri(context: Context, uri: Uri) {
    context
        .getSharedPreferences(READER_DIAGNOSTIC_PREFS, Context.MODE_PRIVATE)
        .edit()
        .putString(READER_LOG_FOLDER_URI_KEY, uri.toString())
        .apply()
}

private fun startReaderLogFile(
    context: Context,
    folderUriText: String?,
    scope: kotlinx.coroutines.CoroutineScope,
    loggingEnabled: Boolean = true,
) {
    if (!loggingEnabled) {
        ReaderDiagnosticLog.clearSink()
        return
    }
    if (folderUriText.isNullOrBlank()) return
    runCatching {
        createReaderLogFile(context, Uri.parse(folderUriText), scope)
    }.fold(
        onSuccess = { logFile ->
            ReaderDiagnosticLog.setSink(logFile.sink)
            ReaderDiagnosticLog.event("log_file_created fileName=${logFile.fileName} uri=${logFile.uri}")
        },
        onFailure = { error ->
            ReaderDiagnosticLog.error("log_file_create_failed folderUri=$folderUriText", error)
        },
    )
}

private fun DownloadProgressUi.toReaderLoadingProgress(): ReaderLoadingProgress =
    ReaderLoadingProgress(downloadedBytes = downloadedBytes, totalBytes = totalBytes)

private const val READER_DIAGNOSTIC_PREFS = "reader_diagnostics"
private const val READER_LOG_FOLDER_URI_KEY = "log_folder_uri"
