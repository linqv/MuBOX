package com.example.comicdav

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.datastore.preferences.preferencesDataStore
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.comicdav.data.ComicDownloadCache
import com.example.comicdav.data.LocalComicImportCache
import com.example.comicdav.data.ReadingProgressStore
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
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val Context.readingProgressDataStore by preferencesDataStore(name = "reading_progress")

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
    val uiState = webDavViewModel.uiState
    val readerUiState = readerViewModel.uiState
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var isReaderOpen by rememberSaveable { mutableStateOf(false) }
    var localOpenError by remember { mutableStateOf<String?>(null) }
    var downloadProgress by remember { mutableStateOf<DownloadProgressUi?>(null) }
    var logFolderUriText by rememberSaveable { mutableStateOf(loadReaderLogFolderUri(context)) }
    val remoteCache = remember(context) { ComicDownloadCache(File(context.cacheDir, "remote-comics")) }
    val progressStore = remember(context) { ReadingProgressStore(context.readingProgressDataStore) }
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
    val localFilePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        startReaderLogFile(context, logFolderUriText, scope)
        ReaderDiagnosticLog.event("local_file_selected uri=$uri")
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    copyUriToCache(context, uri)
                }
            }.fold(
                onSuccess = { cachedFile ->
                    localOpenError = null
                    ReaderDiagnosticLog.event("open_local_cache_ready path=${cachedFile.name} size=${cachedFile.length()}")
                    readerViewModel.openLocal(cachedFile.absolutePath, context.cacheDir)
                    isReaderOpen = true
                },
                onFailure = { error ->
                    ReaderDiagnosticLog.error("open_local_copy_failed", error)
                    localOpenError = error.message ?: "Failed to open local file"
                },
            )
        }
    }

    MaterialTheme {
        Surface(modifier = Modifier.fillMaxSize()) {
            if (isReaderOpen) {
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
            } else if (uiState.status == "Connected") {
                WebDavBrowserScreen(
                    uiState = uiState,
                    onItemClick = { item ->
                        if (item.isDirectory) {
                            webDavViewModel.openDirectory(item)
                        } else {
                            val client = webDavViewModel.activeClient() ?: return@WebDavBrowserScreen
                            downloadProgress = null
                            localOpenError = null
                            isReaderOpen = true
                            startReaderLogFile(context, logFolderUriText, scope)
                            ReaderDiagnosticLog.event("open_remote_start path=${item.path} size=${item.size ?: -1}")
                            readerViewModel.openRemote(cacheDir = context.cacheDir) {
                                val useCase = OpenComicUseCase(
                                    accountId = webDavViewModel.accountId(),
                                    cache = remoteCache,
                                    progressStore = progressStore,
                                )
                                useCase.open(
                                    client = client,
                                    remotePath = item.path,
                                    knownInfo = item.size?.let { size ->
                                        RemoteFileInfo(
                                            path = item.path,
                                            size = size,
                                            etag = item.etag,
                                            lastModified = item.lastModified,
                                            supportsRange = true,
                                        )
                                    },
                                ) { downloaded, total ->
                                    scope.launch {
                                        downloadProgress = DownloadProgressUi(downloaded, total)
                                    }
                                }
                            }
                        }
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
                        localFilePicker.launch(
                            arrayOf(
                                "application/zip",
                                "application/octet-stream",
                                "application/x-cbz",
                                "*/*",
                            ),
                        )
                    },
                )
            }
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
