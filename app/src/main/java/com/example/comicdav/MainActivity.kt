package com.example.comicdav

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.DocumentsContract
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.datastore.preferences.preferencesDataStore
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
import androidx.room.Room
import com.example.comicdav.data.ComicCacheKey
import com.example.comicdav.data.AppDataFolderStore
import com.example.comicdav.data.ComicDownloadCache
import com.example.comicdav.data.filedirectory.FileDirectoryRepository
import com.example.comicdav.data.filedirectory.FileDirectorySourceType
import com.example.comicdav.data.LocalComicImportCache
import com.example.comicdav.data.ReadingProgressStore
import com.example.comicdav.feature.filedirectory.AndroidLocalDirectoryReader
import com.example.comicdav.feature.filedirectory.FileDirectoryBrowserItem
import com.example.comicdav.feature.filedirectory.FileDirectoryScreen
import com.example.comicdav.feature.filedirectory.FileDirectoryViewModel
import com.example.comicdav.data.library.LibraryDatabase
import com.example.comicdav.data.library.LibraryItemWithSources
import com.example.comicdav.data.library.LibraryRepository
import com.example.comicdav.data.library.SourceType
import com.example.comicdav.feature.library.LibraryScreen
import com.example.comicdav.feature.library.LibraryViewModel
import com.example.comicdav.nativebridge.ComicEngine
import com.example.comicdav.nativebridge.ComicReaderSession
import com.example.comicdav.nativebridge.RangeProviderRegistry
import com.example.comicdav.feature.reader.ReaderDiagnosticLog
import com.example.comicdav.feature.reader.ReaderLoadingProgress
import com.example.comicdav.feature.reader.ReaderScreen
import com.example.comicdav.feature.reader.ReaderViewModel
import com.example.comicdav.feature.reader.OpenComicUseCase
import com.example.comicdav.feature.reader.createReaderLogFile
import com.example.comicdav.feature.webdav.DownloadProgressUi
import com.example.comicdav.feature.webdav.WebDavAccountScreen
import com.example.comicdav.feature.webdav.WebDavBrowserScreen
import com.example.comicdav.feature.webdav.WebDavViewModel
import com.example.comicdav.network.RemoteFileInfo
import com.example.comicdav.network.WebDavClient
import com.example.comicdav.network.WebDavItem
import com.example.comicdav.network.WebDavRangeProvider
import com.example.comicdav.ui.ComicDavTheme
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val Context.readingProgressDataStore by preferencesDataStore(name = "reading_progress")
private val Context.appDataFolderDataStore by preferencesDataStore(name = "app_data_folder")

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
        Room.databaseBuilder(
            context.applicationContext,
            LibraryDatabase::class.java,
            "comicdav-library.db",
        ).build()
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
    var isLibraryOpen by rememberSaveable { mutableStateOf(false) }
    var localOpenError by remember { mutableStateOf<String?>(null) }
    var downloadProgress by remember { mutableStateOf<DownloadProgressUi?>(null) }
    var logFolderUriText by rememberSaveable { mutableStateOf(loadReaderLogFolderUri(context)) }
    var dataFolderUriText by rememberSaveable { mutableStateOf<String?>(null) }
    var isDataFolderLoading by remember { mutableStateOf(true) }
    val remoteCache = remember(context) { ComicDownloadCache(File(context.cacheDir, "remote-comics")) }
    val progressStore = remember(context) { ReadingProgressStore(context.readingProgressDataStore) }
    val dataFolderStore = remember(context) { AppDataFolderStore(context.appDataFolderDataStore) }
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
        startReaderLogFile(context, logFolderUriText, scope)
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

    fun openLocalLibraryComic(item: LibraryItemWithSources) {
        val source = item.localSource ?: run {
            localOpenError = "Local source is missing"
            return
        }
        startReaderLogFile(context, logFolderUriText, scope)
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    copyUriToCache(context, Uri.parse(source.uri))
                }
            }.fold(
                onSuccess = { cachedFile ->
                    localOpenError = null
                    libraryViewModel.markOpened(item.item.id)
                    ReaderDiagnosticLog.event("open_library_local_cache_ready path=${cachedFile.name} size=${cachedFile.length()}")
                    readerViewModel.openLocal(
                        path = cachedFile.absolutePath,
                        cacheDir = context.cacheDir,
                        comicKey = "library-${item.item.id}",
                    )
                    isReaderOpen = true
                },
                onFailure = { error ->
                    ReaderDiagnosticLog.error("open_library_local_copy_failed", error)
                    localOpenError = error.message ?: "Failed to open local file"
                },
            )
        }
    }

    fun openLocalDirectoryComic(item: FileDirectoryBrowserItem) {
        startReaderLogFile(context, logFolderUriText, scope)
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    copyUriToCache(context, Uri.parse(item.uri))
                }
            }.fold(
                onSuccess = { cachedFile ->
                    localOpenError = null
                    ReaderDiagnosticLog.event("open_directory_local_cache_ready path=${cachedFile.name} size=${cachedFile.length()}")
                    readerViewModel.openLocal(
                        path = cachedFile.absolutePath,
                        cacheDir = context.cacheDir,
                        comicKey = "directory-${item.uri.hashCode()}",
                    )
                    isReaderOpen = true
                },
                onFailure = { error ->
                    ReaderDiagnosticLog.error("open_directory_local_copy_failed uri=${item.uri}", error)
                    fileDirectoryViewModel.showError(error.message ?: "Failed to open local file")
                },
            )
        }
    }

    fun favoriteLocalDirectoryComic(item: FileDirectoryBrowserItem) {
        scope.launch {
            runCatching {
                val libraryItemId = libraryRepository.addLocalComic(
                    uri = item.uri,
                    fileName = item.name,
                    size = item.size,
                    lastModified = item.lastModified,
                )
                cacheLocalCover(
                    context = context,
                    repository = libraryRepository,
                    libraryItemId = libraryItemId,
                    uri = Uri.parse(item.uri),
                )
            }.fold(
                onSuccess = {
                    fileDirectoryViewModel.showMessage("${item.name} added to library")
                },
                onFailure = { error ->
                    ReaderDiagnosticLog.error("favorite_local_directory_comic_failed uri=${item.uri}", error)
                    fileDirectoryViewModel.showError(error.message ?: "Failed to favorite local comic")
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
            localOpenError = "Connect to $accountId before opening this WebDAV comic"
            isWebDavOpen = true
            return
        }
        downloadProgress = null
        localOpenError = null
        isReaderOpen = true
        startReaderLogFile(context, logFolderUriText, scope)
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

    ComicDavTheme {
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
                            logFolderPicker.launch(null)
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
                    )
                }

                isWebDavOpen -> {
                    if (uiState.status == "Connected") {
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
                            onSelectItem = webDavViewModel::selectItem,
                            onAddToLibrary = { item ->
                                webDavViewModel.selectItem(item)
                                val client = webDavViewModel.activeClient()
                                val accountId = webDavViewModel.activeAccountId() ?: webDavViewModel.accountId()
                                if (client == null) {
                                    libraryViewModel.showError("Connect to WebDAV before adding this comic")
                                } else {
                                    scope.launch {
                                        runCatching {
                                            val libraryItemId = libraryRepository.addWebDavComic(
                                                accountId = accountId,
                                                remotePath = item.path,
                                                fileName = item.name,
                                                size = item.size,
                                                etag = item.etag,
                                                lastModified = item.lastModified,
                                            )
                                            cacheWebDavCover(
                                                context = context,
                                                repository = libraryRepository,
                                                accountId = accountId,
                                                client = client,
                                                item = item,
                                                libraryItemId = libraryItemId,
                                            )
                                        }.fold(
                                            onSuccess = {
                                                libraryViewModel.showMessage("${item.name} added to library")
                                                fileDirectoryViewModel.showMessage("${item.name} added to library")
                                            },
                                            onFailure = { error ->
                                                ReaderDiagnosticLog.error("add_webdav_library_failed path=${item.path}", error)
                                                libraryViewModel.showError(error.message ?: "Failed to add WebDAV comic")
                                                fileDirectoryViewModel.showError(error.message ?: "Failed to add WebDAV comic")
                                            },
                                        )
                                    }
                                }
                            },
                            onSaveDirectory = {
                                val accountId = webDavViewModel.activeAccountId() ?: webDavViewModel.accountId()
                                fileDirectoryViewModel.addWebDavDirectory(
                                    displayName = uiState.currentPath,
                                    accountId = accountId,
                                    path = uiState.currentPath,
                                )
                            },
                            onBackToDirectories = {
                                isWebDavOpen = false
                                localOpenError = null
                            },
                            onProbeTail = webDavViewModel::probeTail64KiB,
                            downloadProgress = downloadProgress,
                            downloadError = localOpenError,
                            onCancelDownload = {
                                downloadProgress = null
                            },
                        )
                    } else {
                        WebDavAccountScreen(
                            uiState = uiState,
                            onBaseUrlChange = webDavViewModel::updateBaseUrl,
                            onUsernameChange = webDavViewModel::updateUsername,
                            onPasswordChange = webDavViewModel::updatePassword,
                            onTestConnection = webDavViewModel::testConnection,
                            onOpenLocal = {
                                localDirectoryPicker.launch(null)
                            },
                            onBackToLibrary = {
                                isWebDavOpen = false
                                localOpenError = null
                            },
                            message = localOpenError,
                        )
                    }
                }

                isLibraryOpen -> {
                    LibraryScreen(
                        uiState = libraryUiState.copy(error = libraryUiState.error ?: localOpenError),
                        onOpenItem = { item ->
                            when (item.item.sourceType) {
                                SourceType.LOCAL -> openLocalLibraryComic(item)
                                SourceType.WEBDAV -> {
                                    val source = item.webDavSource
                                    if (source == null) {
                                        localOpenError = "WebDAV source is missing"
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
                            isLibraryOpen = false
                        },
                        onDismissMessage = {
                            localOpenError = null
                            libraryViewModel.clearMessage()
                        },
                    )
                }

                else -> {
                    FileDirectoryScreen(
                        uiState = fileDirectoryUiState.copy(error = fileDirectoryUiState.error ?: localOpenError),
                        onAddLocalDirectory = {
                            localDirectoryPicker.launch(null)
                        },
                        onOpenWebDav = {
                            localOpenError = null
                            isWebDavOpen = true
                        },
                        onOpenLibrary = {
                            localOpenError = null
                            isLibraryOpen = true
                        },
                        onOpenSource = { source ->
                            when (source.sourceType) {
                                FileDirectorySourceType.LOCAL -> {
                                    fileDirectoryViewModel.openLocalSource(source)
                                }
                                FileDirectorySourceType.WEBDAV -> {
                                    val expectedAccountId = source.webDavAccountId
                                    val path = source.webDavPath ?: "/"
                                    if (expectedAccountId != null && webDavViewModel.activeAccountId() == expectedAccountId) {
                                        webDavViewModel.openPath(path)
                                    } else {
                                        localOpenError = "Connect to ${expectedAccountId.orEmpty()} before opening this WebDAV directory"
                                    }
                                    isWebDavOpen = true
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
                    )
                }
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
            text = "Choose a ComicDav data folder",
            style = MaterialTheme.typography.headlineSmall,
        )
        Text(
            text = "ComicDav stores covers, offline comics, diagnostics, and future exports in a folder you control.",
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 10.dp, bottom = 20.dp),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Button(onClick = onChooseFolder) {
            Text("Choose Folder")
        }
    }
}

private fun copyUriToCache(context: Context, uri: Uri): File {
    LocalComicImportCache.prune(context.cacheDir)
    val target = LocalComicImportCache.targetFile(context.cacheDir)
    target.parentFile?.mkdirs()
    context.contentResolver.openInputStream(uri).use { input ->
        requireNotNull(input) { "Could not read selected file" }
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
    return treeUri.lastPathSegment?.substringAfterLast(':')?.ifBlank { null } ?: "Local Folder"
}

private suspend fun cacheLocalCover(
    context: Context,
    repository: LibraryRepository,
    libraryItemId: Long,
    uri: Uri,
) {
    runCatching {
        withContext(Dispatchers.IO) {
            val cachedFile = copyUriToCache(context, uri)
            ComicEngine().openLocal(cachedFile.absolutePath).use { session ->
                writeCoverFromSession(context, repository, libraryItemId, session)
            }
        }
    }.onFailure { error ->
        ReaderDiagnosticLog.error("cache_local_cover_failed item=$libraryItemId uri=$uri", error)
    }
}

private suspend fun cacheWebDavCover(
    context: Context,
    repository: LibraryRepository,
    accountId: String,
    client: WebDavClient,
    item: WebDavItem,
    libraryItemId: Long,
) {
    runCatching {
        withContext(Dispatchers.IO) {
            val info = item.size?.let { size ->
                RemoteFileInfo(
                    path = item.path,
                    size = size,
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
            val fileId = RangeProviderRegistry.register(WebDavRangeProvider(client, item.path, info.size))
            var session: ComicReaderSession? = null
            try {
                session = ComicEngine().openRemote(
                    fileId = fileId,
                    size = info.size,
                    cacheDir = context.cacheDir,
                    comicKey = key.value,
                    validator = info.etag ?: info.lastModified?.toString() ?: info.size.toString(),
                )
                writeCoverFromSession(context, repository, libraryItemId, session)
            } finally {
                session?.close() ?: RangeProviderRegistry.unregister(fileId)
            }
        }
    }.onFailure { error ->
        ReaderDiagnosticLog.error("cache_webdav_cover_failed item=$libraryItemId path=${item.path}", error)
    }
}

private suspend fun writeCoverFromSession(
    context: Context,
    repository: LibraryRepository,
    libraryItemId: Long,
    session: ComicReaderSession,
) {
    if (session.pageCount <= 0) return
    val coverDir = File(context.filesDir, "library-covers")
    coverDir.mkdirs()
    val coverFile = File(coverDir, "$libraryItemId-page-0.img")
    session.loadPageToFile(0, coverFile)
    repository.updatePresentationMetadata(
        libraryItemId = libraryItemId,
        coverPath = coverFile.absolutePath,
        pageCount = session.pageCount,
    )
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
) {
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
