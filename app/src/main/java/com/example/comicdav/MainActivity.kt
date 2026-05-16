package com.example.comicdav

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.net.Uri
import android.os.Bundle
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
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.example.comicdav.data.AppDataFolderStore
import com.example.comicdav.data.AppSettings
import com.example.comicdav.data.AppSettingsStore
import com.example.comicdav.data.ComicCacheKey
import com.example.comicdav.data.ComicDownloadCache
import com.example.comicdav.data.filedirectory.FileDirectoryRepository
import com.example.comicdav.data.filedirectory.FileDirectorySourceType
import com.example.comicdav.data.LocalComicImportCache
import com.example.comicdav.data.ReadingProgressStore
import com.example.comicdav.data.WebDavAccountStore
import com.example.comicdav.feature.filedirectory.AndroidLocalDirectoryReader
import com.example.comicdav.feature.filedirectory.FileDirectoryBrowserItem
import com.example.comicdav.feature.filedirectory.FileDirectoryScreen
import com.example.comicdav.data.filedirectory.FileDirectorySourceEntity
import com.example.comicdav.feature.filedirectory.FileDirectoryViewModel
import com.example.comicdav.data.library.LibraryItemWithSources
import com.example.comicdav.data.library.LibraryRepository
import com.example.comicdav.data.library.SourceType
import com.example.comicdav.data.library.createLibraryDatabase
import com.example.comicdav.feature.library.LibraryScreen
import com.example.comicdav.feature.library.LibraryViewModel
import com.example.comicdav.feature.reader.ReaderDiagnosticLog
import com.example.comicdav.feature.reader.ReaderLoadingProgress
import com.example.comicdav.feature.reader.ReaderScreen
import com.example.comicdav.feature.reader.ReaderViewModel
import com.example.comicdav.feature.reader.OpenComicUseCase
import com.example.comicdav.feature.reader.createReaderLogFile
import com.example.comicdav.feature.settings.SettingsScreen
import com.example.comicdav.feature.webdav.DownloadProgressUi
import com.example.comicdav.feature.webdav.WEB_DAV_STATUS_CONNECTED
import com.example.comicdav.feature.webdav.WebDavAccountScreen
import com.example.comicdav.feature.webdav.WebDavBrowserScreen
import com.example.comicdav.feature.webdav.WebDavViewModel
import com.example.comicdav.network.RemoteFileInfo
import com.example.comicdav.network.WebDavItem
import com.example.comicdav.ui.ComicDavCopy
import com.example.comicdav.ui.ComicDavTheme
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val Context.readingProgressDataStore by preferencesDataStore(name = "reading_progress")
private val Context.appDataFolderDataStore by preferencesDataStore(name = "app_data_folder")
private val Context.appSettingsDataStore by preferencesDataStore(name = "app_settings")
private val Context.webDavAccountDataStore by preferencesDataStore(name = "webdav_accounts")

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
    val fileDirectoryRepository = remember(libraryDatabase) {
        FileDirectoryRepository(libraryDatabase.fileDirectoryDao())
    }
    val localDirectoryReader = remember(context) {
        AndroidLocalDirectoryReader(context.applicationContext)
    }
    val libraryViewModel: LibraryViewModel = viewModel(
        factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                return LibraryViewModel(libraryRepository) as T
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
    val fileDirectoryUiState = fileDirectoryViewModel.uiState
    val scope = rememberCoroutineScope()
    var isReaderOpen by rememberSaveable { mutableStateOf(false) }
    var isWebDavOpen by rememberSaveable { mutableStateOf(false) }
    var selectedTabName by rememberSaveable { mutableStateOf(AppTab.SOURCES.name) }
    val selectedTab = remember(selectedTabName) {
        runCatching { AppTab.valueOf(selectedTabName) }.getOrDefault(AppTab.SOURCES)
    }
    var localOpenError by remember { mutableStateOf<String?>(null) }
    var webDavActionMessage by remember { mutableStateOf<String?>(null) }
    var downloadProgress by remember { mutableStateOf<DownloadProgressUi?>(null) }
    var logFolderUriText by rememberSaveable { mutableStateOf(loadReaderLogFolderUri(context)) }
    var dataFolderUriText by rememberSaveable { mutableStateOf<String?>(null) }
    var isDataFolderLoading by remember { mutableStateOf(true) }
    val remoteCache = remember(context) { ComicDownloadCache(File(context.cacheDir, "remote-comics")) }
    val progressStore = remember(context) { ReadingProgressStore(context.readingProgressDataStore) }
    val dataFolderStore = remember(context) { AppDataFolderStore(context.appDataFolderDataStore) }
    val appSettingsStore = remember(context) { AppSettingsStore(context.appSettingsDataStore) }
    val webDavAccountStore = remember(context) { WebDavAccountStore(context.webDavAccountDataStore) }
    val appSettings by appSettingsStore.settings.collectAsState(initial = AppSettings())
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
        startReaderLogFile(context, logFolderUriText, scope, appSettings.loggingEnabled)
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
        (context as? Activity)?.requestedOrientation = if (appSettings.screenRotationLockEnabled) {
            ActivityInfo.SCREEN_ORIENTATION_LOCKED
        } else {
            ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
    }

    LaunchedEffect(appSettings.loggingEnabled) {
        if (!appSettings.loggingEnabled) {
            ReaderDiagnosticLog.clearSink()
        }
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

    fun openCachedLocalComic(
        uri: Uri,
        comicKey: String,
        readyEvent: String,
        failureEvent: String,
        onOpened: (File) -> Unit = {},
        onFailure: (Throwable) -> Unit,
    ) {
        startReaderLogFile(context, logFolderUriText, scope, appSettings.loggingEnabled)
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    copyUriToCache(context, uri)
                }
            }.fold(
                onSuccess = { cachedFile ->
                    localOpenError = null
                    onOpened(cachedFile)
                    ReaderDiagnosticLog.event("$readyEvent path=${cachedFile.name} size=${cachedFile.length()}")
                    readerViewModel.openLocal(
                        path = cachedFile.absolutePath,
                        cacheDir = context.cacheDir,
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
        openCachedLocalComic(
            uri = Uri.parse(source.uri),
            comicKey = "library-${item.item.id}",
            readyEvent = "open_library_local_cache_ready",
            failureEvent = "open_library_local_copy_failed",
            onOpened = { libraryViewModel.markOpened(item.item.id) },
            onFailure = { error -> localOpenError = error.message ?: "打开本地文件失败" },
        )
    }

    fun openLocalDirectoryComic(item: FileDirectoryBrowserItem) {
        openCachedLocalComic(
            uri = Uri.parse(item.uri),
            comicKey = "directory-${item.uri.hashCode()}",
            readyEvent = "open_directory_local_cache_ready",
            failureEvent = "open_directory_local_copy_failed uri=${item.uri}",
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
                libraryRepository.addWebDavComic(
                    accountId = accountId,
                    remotePath = item.path,
                    fileName = item.name,
                    size = item.size,
                    etag = item.etag,
                    lastModified = item.lastModified,
                )
            }.fold(
                onSuccess = {
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
            }.fold(
                onSuccess = {
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
        startReaderLogFile(context, logFolderUriText, scope, appSettings.loggingEnabled)
        ReaderDiagnosticLog.event("open_remote_start path=$remotePath size=${size ?: -1}")
        readerViewModel.openRemote(cacheDir = context.cacheDir) {
            val useCase = OpenComicUseCase(
                accountId = accountId,
                cache = remoteCache,
                progressStore = progressStore,
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
            ) { downloaded, total ->
                scope.launch {
                    downloadProgress = DownloadProgressUi(downloaded, total)
                }
            }
            onOpenSucceeded?.invoke()
            result
        }
    }

    BackHandler(
        enabled = isReaderOpen ||
            isWebDavOpen ||
            fileDirectoryUiState.currentTitle != null ||
            selectedTab != AppTab.SOURCES,
    ) {
        when {
            isReaderOpen -> closeReaderFromNavigation()
            isWebDavOpen -> {
                if (!webDavViewModel.handleBack()) {
                    isWebDavOpen = false
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
                        },
                    ) { contentModifier ->
                        when (selectedTab) {
                            AppTab.SOURCES -> {
                                if (isWebDavOpen) {
                                    if (uiState.status == WEB_DAV_STATUS_CONNECTED) {
                                        WebDavBrowserScreen(
                                            uiState = uiState,
                                            onItemClick = { item ->
                                                if (item.isDirectory) {
                                                    webDavViewModel.openDirectory(item)
                                                } else {
                                                    openRemoteComic(
                                                        accountId = webDavViewModel.activeAccountId() ?: webDavViewModel.accountId(),
                                                        remotePath = item.path,
                                                        size = item.size,
                                                        etag = item.etag,
                                                        lastModified = item.lastModified,
                                                    )
                                                }
                                            },
                                            onAddToLibrary = ::favoriteWebDavComic,
                                            onDownloadToLocal = ::downloadWebDavComicToLocal,
                                            onSaveDirectory = {
                                                val accountId = webDavViewModel.activeAccountId() ?: webDavViewModel.accountId()
                                                fileDirectoryViewModel.addWebDavDirectory(
                                                    displayName = uiState.currentPath,
                                                    accountId = accountId,
                                                    path = uiState.currentPath,
                                                    baseUrl = uiState.baseUrl,
                                                    username = uiState.username,
                                                    password = uiState.password,
                                                )
                                            },
                                            onBackToDirectories = {
                                                isWebDavOpen = false
                                                localOpenError = null
                                                webDavActionMessage = null
                                            },
                                            downloadProgress = downloadProgress,
                                            downloadError = localOpenError,
                                            actionMessage = webDavActionMessage,
                                            onCancelDownload = {
                                                downloadProgress = null
                                            },
                                            modifier = contentModifier,
                                        )
                                    } else {
                                        WebDavAccountScreen(
                                            uiState = uiState,
                                            onBaseUrlChange = webDavViewModel::updateBaseUrl,
                                            onUsernameChange = webDavViewModel::updateUsername,
                                            onPasswordChange = webDavViewModel::updatePassword,
                                            onTestConnection = webDavViewModel::testConnection,
                                            onBackToLibrary = {
                                                isWebDavOpen = false
                                                localOpenError = null
                                                webDavActionMessage = null
                                            },
                                            message = localOpenError,
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
                                            webDavViewModel.startNewConnection()
                                            isWebDavOpen = true
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
                                        onFavoriteComic = ::favoriteLocalDirectoryComic,
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
                                    onOpenDirectories = {
                                        localOpenError = null
                                        selectedTabName = AppTab.SOURCES.name
                                    },
                                    onDismissMessage = {
                                        localOpenError = null
                                        libraryViewModel.clearMessage()
                                    },
                                    modifier = contentModifier,
                                )
                            }
                            AppTab.SETTINGS -> {
                                SettingsScreen(
                                    settings = appSettings,
                                    onReadingDirectionChange = { value ->
                                        scope.launch { appSettingsStore.updateReadingDirection(value) }
                                    },
                                    onLoggingEnabledChange = { value ->
                                        scope.launch { appSettingsStore.updateLoggingEnabled(value) }
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

private enum class AppTab {
    SOURCES,
    LIBRARY,
    SETTINGS;

    val label: String
        get() = when (this) {
            SOURCES -> ComicDavCopy.sourcesTab
            LIBRARY -> ComicDavCopy.libraryTab
            SETTINGS -> ComicDavCopy.settingsTab
        }

    val compactIcon: String
        get() = when (this) {
            SOURCES -> "源"
            LIBRARY -> "书"
            SETTINGS -> "设"
        }
}

@Composable
private fun ComicDavAppShell(
    selectedTab: AppTab,
    onTabSelected: (AppTab) -> Unit,
    modifier: Modifier = Modifier,
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
        NavigationBar(
            containerColor = MaterialTheme.colorScheme.surface,
            tonalElevation = 3.dp,
        ) {
            AppTab.values().forEach { tab ->
                NavigationBarItem(
                    selected = selectedTab == tab,
                    onClick = { onTabSelected(tab) },
                    icon = {
                        Text(
                            text = tab.compactIcon,
                            style = MaterialTheme.typography.labelLarge,
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

private fun copyUriToCache(context: Context, uri: Uri): File {
    LocalComicImportCache.prune(context.cacheDir)
    val target = LocalComicImportCache.targetFile(context.cacheDir)
    target.parentFile?.mkdirs()
    context.contentResolver.openInputStream(uri).use { input ->
        requireNotNull(input) { "无法读取所选文件" }
        target.outputStream().use { output ->
            input.copyTo(output)
        }
    }
    LocalComicImportCache.prune(context.cacheDir, protectedFile = target)
    return target
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
    return treeUri.lastPathSegment?.substringAfterLast(':')?.ifBlank { null } ?: "本地文件夹"
}

private fun deleteLocalSourceTree(context: Context, treeUri: Uri) {
    val rootDocumentUri = DocumentsContract.buildDocumentUriUsingTree(
        treeUri,
        DocumentsContract.getTreeDocumentId(treeUri),
    )
    val deleted = DocumentsContract.deleteDocument(context.contentResolver, rootDocumentUri)
    check(deleted) { "系统未允许删除这个本地文件夹" }
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
