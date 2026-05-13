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
import com.example.comicdav.feature.reader.ReaderScreen
import com.example.comicdav.feature.reader.ReaderViewModel
import com.example.comicdav.feature.webdav.DownloadProgressUi
import com.example.comicdav.feature.webdav.WebDavAccountScreen
import com.example.comicdav.feature.webdav.WebDavBrowserScreen
import com.example.comicdav.feature.webdav.WebDavViewModel
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val Context.readingProgressDataStore by preferencesDataStore(name = "reading_progress")

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { ComicDavApp() }
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
    var downloadJob by remember { mutableStateOf<Job?>(null) }
    var currentComicKey by remember { mutableStateOf<String?>(null) }
    val remoteCache = remember(context) { ComicDownloadCache(File(context.cacheDir, "remote-comics")) }
    val progressStore = remember(context) { ReadingProgressStore(context.readingProgressDataStore) }
    val localFilePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    copyUriToCache(context, uri)
                }
            }.fold(
                onSuccess = { cachedFile ->
                    localOpenError = null
                    currentComicKey = null
                    readerViewModel.openLocal(cachedFile.absolutePath, context.cacheDir)
                    isReaderOpen = true
                },
                onFailure = { error ->
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
                    onPageChanged = { page ->
                        readerViewModel.selectPage(page)
                        currentComicKey?.let { key ->
                            scope.launch {
                                progressStore.savePage(key, page)
                            }
                        }
                    },
                    onClose = {
                        readerViewModel.closeReader()
                        currentComicKey = null
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
                            downloadJob?.cancel()
                            downloadProgress = DownloadProgressUi(0, item.size ?: 0)
                            downloadJob = scope.launch {
                                runCatching {
                                    val useCase = OpenComicUseCase(
                                        accountId = webDavViewModel.accountId(),
                                        cache = remoteCache,
                                        progressStore = progressStore,
                                    )
                                    useCase.open(client, item.path) { downloaded, total ->
                                        scope.launch {
                                            downloadProgress = DownloadProgressUi(downloaded, total)
                                        }
                                    }
                                }.fold(
                                    onSuccess = { result ->
                                        downloadProgress = null
                                        localOpenError = null
                                        currentComicKey = result.comicKey
                                        readerViewModel.openExistingSession(
                                            openedSession = result.session,
                                            cacheDir = context.cacheDir,
                                            initialPage = result.initialPage,
                                            comicKey = result.comicKey,
                                        )
                                        isReaderOpen = true
                                    },
                                    onFailure = { error ->
                                        downloadProgress = null
                                        if (error !is CancellationException) {
                                            localOpenError = error.message ?: "Failed to open remote comic"
                                        }
                                    },
                                )
                            }
                        }
                    },
                    onProbeTail = webDavViewModel::probeTail64KiB,
                    downloadProgress = downloadProgress,
                    downloadError = localOpenError,
                    onCancelDownload = {
                        downloadJob?.cancel()
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
