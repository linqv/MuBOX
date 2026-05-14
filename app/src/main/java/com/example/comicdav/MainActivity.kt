package com.example.comicdav

import android.content.Context
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
import com.example.comicdav.data.ReadingProgressStore
import com.example.comicdav.feature.reader.OpenComicUseCase
import com.example.comicdav.feature.reader.ContentUriReaderLogSink
import com.example.comicdav.feature.reader.ReaderDiagnosticLog
import com.example.comicdav.feature.reader.ReaderScreen
import com.example.comicdav.feature.reader.ReaderViewModel
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
    val remoteCache = remember(context) { ComicDownloadCache(File(context.cacheDir, "remote-comics")) }
    val progressStore = remember(context) { ReadingProgressStore(context.readingProgressDataStore) }
    val logFileCreator = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        if (uri == null) {
            ReaderDiagnosticLog.event("log_file_cancelled")
            return@rememberLauncherForActivityResult
        }
        ReaderDiagnosticLog.setSink(ContentUriReaderLogSink(context, uri, scope))
        ReaderDiagnosticLog.event("log_file_selected uri=$uri")
    }
    val localFilePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
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
                    onChooseLogFile = {
                        logFileCreator.launch("comicdav-reader-log-${System.currentTimeMillis()}.txt")
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
    val target = File(context.cacheDir, "local-comic-${System.currentTimeMillis()}.cbz")
    context.contentResolver.openInputStream(uri).use { input ->
        requireNotNull(input) { "Could not read selected file" }
        target.outputStream().use { output ->
            input.copyTo(output)
        }
    }
    return target
}
