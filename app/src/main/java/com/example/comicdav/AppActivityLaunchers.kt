package com.example.comicdav

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import com.example.comicdav.core.diagnostics.ConfigurableDiagnostics
import com.example.comicdav.data.AppDataFolderStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

internal data class AppActivityLaunchers(
    val chooseDataFolder: () -> Unit,
    val chooseLogFolder: () -> Unit,
    val chooseLocalDirectory: () -> Unit,
    val openVideoPlayer: (Intent) -> Unit,
)

@Composable
internal fun rememberAppActivityLaunchers(
    context: Context,
    scope: CoroutineScope,
    dataFolderStore: AppDataFolderStore,
    diagnostics: ConfigurableDiagnostics,
    loggingEnabled: Boolean,
    onDataFolderSelected: (String) -> Unit,
    onLogFolderSelected: (String) -> Unit,
    onLocalDirectorySelected: (displayName: String, treeUri: String) -> Unit,
    onVideoPlayerClosed: () -> Unit,
): AppActivityLaunchers {
    val dataFolderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        takePersistableTreePermission(context, uri, diagnostics, "data_folder_permission_failed")
        scope.launch {
            dataFolderStore.saveFolderUri(uri.toString())
            onDataFolderSelected(uri.toString())
        }
    }
    val logFolderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri == null) {
            diagnostics.event("log_folder_cancelled")
            return@rememberLauncherForActivityResult
        }
        takePersistableTreePermission(context, uri, diagnostics, "log_folder_permission_failed")
        saveReaderLogFolderUri(context, uri)
        onLogFolderSelected(uri.toString())
        startReaderLogFile(context, uri.toString(), scope, diagnostics, loggingEnabled)
        diagnostics.event("log_folder_selected uri=$uri")
    }
    val localDirectoryPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        diagnostics.event("local_directory_selected uri=$uri")
        takePersistableTreePermission(context, uri, diagnostics, "local_directory_permission_failed")
        onLocalDirectorySelected(queryDirectoryDisplayName(context, uri), uri.toString())
    }
    val videoPlayerLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        onVideoPlayerClosed()
    }

    return AppActivityLaunchers(
        chooseDataFolder = { dataFolderPicker.launch(null) },
        chooseLogFolder = { logFolderPicker.launch(null) },
        chooseLocalDirectory = { localDirectoryPicker.launch(null) },
        openVideoPlayer = videoPlayerLauncher::launch,
    )
}

private fun takePersistableTreePermission(
    context: Context,
    uri: Uri,
    diagnostics: ConfigurableDiagnostics,
    failureEvent: String,
) {
    val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
    runCatching {
        context.contentResolver.takePersistableUriPermission(uri, flags)
    }.onFailure { error ->
        diagnostics.error("$failureEvent uri=$uri", error)
    }
}
