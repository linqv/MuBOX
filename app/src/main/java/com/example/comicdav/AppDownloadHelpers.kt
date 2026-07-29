package com.example.comicdav

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import com.example.comicdav.core.diagnostics.Diagnostics
import com.example.comicdav.core.model.transfer.TransferProgress
import com.example.comicdav.feature.reader.ReaderLoadingProgress
import com.example.comicdav.ui.decodeWebDavPathForDisplay
import java.io.FileNotFoundException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal fun TransferProgress.toReaderLoadingProgress(): ReaderLoadingProgress =
    ReaderLoadingProgress(downloadedBytes = downloadedBytes, totalBytes = totalBytes)

internal fun shouldRemoveDownloadRecordAfterDelete(
    documentDeleteSucceeded: Boolean,
    documentStillResolvable: Boolean,
): Boolean = documentDeleteSucceeded || !documentStillResolvable

internal fun shouldRemoveVideoDownloadRecordAfterDelete(
    documentDeleteSucceeded: Boolean,
    documentStillResolvable: Boolean,
): Boolean = shouldRemoveDownloadRecordAfterDelete(
    documentDeleteSucceeded = documentDeleteSucceeded,
    documentStillResolvable = documentStillResolvable,
)

internal fun downloadLocalUriTextOrNull(localUri: String?): String? =
    localUri?.trim()?.takeIf { it.isNotBlank() }

internal fun downloadDocumentStillResolvable(
    context: Context,
    uri: Uri,
    diagnostics: Diagnostics,
): Boolean =
    runCatching {
        val stream = context.contentResolver.openInputStream(uri)
        stream?.use { }
        stream != null
    }.getOrElse { error ->
        if (error.causedByFileNotFound()) {
            false
        } else {
            diagnostics.error("resolve_video_download_file_failed uri=$uri", error)
            true
        }
    }

internal fun videoDownloadDocumentStillResolvable(
    context: Context,
    uri: Uri,
    diagnostics: Diagnostics,
): Boolean =
    downloadDocumentStillResolvable(context, uri, diagnostics)

internal suspend fun deleteDownloadDocumentAndShouldRemoveRecord(
    context: Context,
    uri: Uri,
    diagnosticName: String,
    diagnostics: Diagnostics,
): Boolean = withContext(Dispatchers.IO) {
    var documentStillResolvable = true
    val documentDeleteSucceeded = runCatching {
        DocumentsContract.deleteDocument(context.contentResolver, uri)
    }.fold(
        onSuccess = { deleted ->
            if (!deleted) {
                documentStillResolvable = downloadDocumentStillResolvable(context, uri, diagnostics)
                diagnostics.event("${diagnosticName}_returned_false uri=$uri resolvable=$documentStillResolvable")
            }
            deleted
        },
        onFailure = { error ->
            diagnostics.error("${diagnosticName}_failed uri=$uri", error)
            documentStillResolvable = !error.causedByFileNotFound()
            false
        },
    )
    shouldRemoveDownloadRecordAfterDelete(
        documentDeleteSucceeded = documentDeleteSucceeded,
        documentStillResolvable = documentStillResolvable,
    )
}

internal fun Throwable.causedByFileNotFound(): Boolean =
    generateSequence(this as Throwable?) { it.cause }.any { it is FileNotFoundException }

internal fun queryDirectoryDisplayName(context: Context, treeUri: Uri): String {
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

internal fun deleteLocalSourceTree(context: Context, treeUri: Uri) {
    val rootDocumentUri = DocumentsContract.buildDocumentUriUsingTree(
        treeUri,
        DocumentsContract.getTreeDocumentId(treeUri),
    )
    val deleted = DocumentsContract.deleteDocument(context.contentResolver, rootDocumentUri)
    check(deleted) { "系统未允许删除这个本地文件夹" }
}
