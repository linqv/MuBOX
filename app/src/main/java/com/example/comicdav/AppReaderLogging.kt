package com.example.comicdav

import android.content.Context
import android.net.Uri
import com.example.comicdav.core.diagnostics.ConfigurableDiagnostics
import com.example.comicdav.core.diagnostics.DiagnosticVerbosity
import com.example.comicdav.core.model.settings.ReaderLoggingMode
import com.example.comicdav.feature.reader.createReaderLogFile
import com.example.comicdav.feature.reader.runReaderLogIo
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

internal const val READER_DIAGNOSTIC_PREFS = "reader_diagnostics"
internal const val READER_LOG_FOLDER_URI_KEY = "log_folder_uri"
private val readerLogStartGeneration = AtomicLong(0L)

internal fun loadReaderLogFolderUri(context: Context): String? {
    return context
        .getSharedPreferences(READER_DIAGNOSTIC_PREFS, Context.MODE_PRIVATE)
        .getString(READER_LOG_FOLDER_URI_KEY, null)
}

internal fun saveReaderLogFolderUri(context: Context, uri: Uri) {
    context
        .getSharedPreferences(READER_DIAGNOSTIC_PREFS, Context.MODE_PRIVATE)
        .edit()
        .putString(READER_LOG_FOLDER_URI_KEY, uri.toString())
        .apply()
}

internal fun startReaderLogFile(
    context: Context,
    folderUriText: String?,
    scope: CoroutineScope,
    diagnostics: ConfigurableDiagnostics,
    loggingEnabled: Boolean = true,
) {
    val startGeneration = readerLogStartGeneration.incrementAndGet()
    if (!loggingEnabled) {
        diagnostics.clearAdditionalSink()
        return
    }
    if (folderUriText.isNullOrBlank()) return
    scope.launch {
        runCatching {
            runReaderLogIo {
                createReaderLogFile(context, Uri.parse(folderUriText), scope)
            }
        }.fold(
            onSuccess = { logFile ->
                if (readerLogStartGeneration.get() != startGeneration) return@fold
                diagnostics.setAdditionalSink(logFile.sink)
                diagnostics.event("log_file_created fileName=${logFile.fileName} uri=${logFile.uri}")
            },
            onFailure = { error ->
                if (readerLogStartGeneration.get() != startGeneration) return@fold
                diagnostics.error("log_file_create_failed folderUri=$folderUriText", error)
            },
        )
    }
}

internal fun ReaderLoggingMode.toDiagnosticVerbosity(): DiagnosticVerbosity = when (this) {
    ReaderLoggingMode.OFF -> DiagnosticVerbosity.OFF
    ReaderLoggingMode.SUMMARY -> DiagnosticVerbosity.SUMMARY
    ReaderLoggingMode.DETAIL -> DiagnosticVerbosity.DETAIL
}
