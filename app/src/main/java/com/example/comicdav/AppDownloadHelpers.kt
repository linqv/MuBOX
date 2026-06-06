package com.example.comicdav

import android.content.Context
import android.net.Uri
import android.os.SystemClock
import android.provider.DocumentsContract
import com.example.comicdav.data.DownloadRecord
import com.example.comicdav.feature.reader.ReaderDiagnosticLog
import com.example.comicdav.feature.reader.ReaderLoadingProgress
import com.example.comicdav.feature.webdav.DownloadProgressUi
import com.example.comicdav.network.RemoteFileInfo
import com.example.comicdav.network.WebDavClient
import com.example.comicdav.webdav.decodeWebDavPathForDisplay
import java.io.FileNotFoundException
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.job
import kotlinx.coroutines.withContext

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

internal fun DownloadProgressUi.toReaderLoadingProgress(): ReaderLoadingProgress =
    ReaderLoadingProgress(downloadedBytes = downloadedBytes, totalBytes = totalBytes)

internal fun shouldRemoveDownloadRecordAfterDelete(
    documentDeleteSucceeded: Boolean,
    documentStillResolvable: Boolean,
): Boolean =
    documentDeleteSucceeded || !documentStillResolvable

internal fun shouldRemoveVideoDownloadRecordAfterDelete(
    documentDeleteSucceeded: Boolean,
    documentStillResolvable: Boolean,
): Boolean = shouldRemoveDownloadRecordAfterDelete(
    documentDeleteSucceeded = documentDeleteSucceeded,
    documentStillResolvable = documentStillResolvable,
)

internal fun downloadLocalUriTextOrNull(localUri: String?): String? =
    localUri?.trim()?.takeIf { it.isNotBlank() }

internal fun downloadDocumentStillResolvable(context: Context, uri: Uri): Boolean =
    runCatching {
        val stream = context.contentResolver.openInputStream(uri)
        stream?.use { }
        stream != null
    }.getOrElse { error ->
        if (error.causedByFileNotFound()) {
            false
        } else {
            ReaderDiagnosticLog.error("resolve_video_download_file_failed uri=$uri", error)
            true
        }
    }

internal fun videoDownloadDocumentStillResolvable(context: Context, uri: Uri): Boolean =
    downloadDocumentStillResolvable(context, uri)

internal suspend fun deleteDownloadDocumentAndShouldRemoveRecord(
    context: Context,
    uri: Uri,
    diagnosticName: String,
): Boolean = withContext(Dispatchers.IO) {
    var documentStillResolvable = true
    val documentDeleteSucceeded = runCatching {
        DocumentsContract.deleteDocument(context.contentResolver, uri)
    }.fold(
        onSuccess = { deleted ->
            if (!deleted) {
                documentStillResolvable = downloadDocumentStillResolvable(context, uri)
                ReaderDiagnosticLog.event("${diagnosticName}_returned_false uri=$uri resolvable=$documentStillResolvable")
            }
            deleted
        },
        onFailure = { error ->
            ReaderDiagnosticLog.error("${diagnosticName}_failed uri=$uri", error)
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

internal suspend fun copyStreamWithProgress(
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

internal fun sanitizeDownloadedVideoFileName(fileName: String): String {
    val sanitized = fileName
        .replace(Regex("""[\\/:*?"<>|\u0000-\u001F]"""), "_")
        .trim()
        .trim('.')
        .take(180)
    return sanitized.ifBlank { "video-download" }
}

internal fun localDownloadFileNameForRemoteFile(
    accountId: String,
    remotePath: String,
    fileName: String,
): String {
    val safeName = sanitizeDownloadedVideoFileName(fileName)
    val suffix = stableDownloadSuffix(accountId, remotePath)
    val dotIndex = safeName.lastIndexOf('.').takeIf { it > 0 && it < safeName.lastIndex }
    return if (dotIndex == null) {
        val maxBaseLength = (MAX_DOWNLOAD_FILE_NAME_LENGTH - suffix.length - 1).coerceAtLeast(1)
        "${safeName.take(maxBaseLength).trimEnd('.', ' ').ifBlank { "download" }}-$suffix"
    } else {
        val extension = safeName.substring(dotIndex)
        val maxBaseLength = (MAX_DOWNLOAD_FILE_NAME_LENGTH - suffix.length - extension.length - 1).coerceAtLeast(1)
        val baseName = safeName.substring(0, dotIndex)
            .take(maxBaseLength)
            .trimEnd('.', ' ')
            .ifBlank { "download" }
        "$baseName-$suffix$extension"
    }
}

private fun stableDownloadSuffix(accountId: String, remotePath: String): String {
    val digest = MessageDigest.getInstance("SHA-256")
        .digest("$accountId\u001F$remotePath".toByteArray(Charsets.UTF_8))
    return digest.take(DOWNLOAD_FILE_NAME_HASH_BYTES).joinToString("") { byte ->
        "%02x".format(byte.toInt() and 0xff)
    }
}

internal data class DataFolderDownloadResult(
    val localUri: String,
    val sizeBytes: Long,
)

internal fun downloadedFileParentDocumentUri(folderTreeUri: Uri): Uri =
    DocumentsContract.buildDocumentUriUsingTree(
        folderTreeUri,
        DocumentsContract.getTreeDocumentId(folderTreeUri),
    )

internal fun mimeTypeForDownloadFileName(fileName: String): String =
    com.example.comicdav.video.mimeTypeForMediaFileName(fileName)
        ?: when (fileName.substringAfterLast('.', missingDelimiterValue = "").lowercase(Locale.ROOT)) {
            "cbz", "zip" -> "application/zip"
            "cb7", "7z" -> "application/x-7z-compressed"
            "cbt", "tar" -> "application/x-tar"
            "pdf" -> "application/pdf"
            "epub" -> "application/epub+zip"
            "mobi" -> "application/x-mobipocket-ebook"
            "azw3" -> "application/vnd.amazon.ebook"
            else -> "application/octet-stream"
        }

internal suspend fun downloadWebDavVideoToDataFolder(
    context: Context,
    folderTreeUri: Uri,
    client: com.example.comicdav.network.WebDavClient,
    accountId: String,
    remotePath: String,
    fileName: String,
    expectedSize: Long,
    onProgress: (downloadedBytes: Long, totalBytes: Long) -> Unit,
): String =
    downloadWebDavFileToDataFolder(
        context = context,
        folderTreeUri = folderTreeUri,
        client = client,
        remotePath = remotePath,
        fileName = fileName,
        localFileName = localDownloadFileNameForRemoteFile(accountId, remotePath, fileName),
        expectedSize = expectedSize,
        onProgress = onProgress,
    ).localUri

internal suspend fun downloadWebDavFileToDataFolder(
    context: Context,
    folderTreeUri: Uri,
    client: WebDavClient,
    remotePath: String,
    fileName: String,
    localFileName: String = fileName,
    expectedSize: Long,
    onProgress: (downloadedBytes: Long, totalBytes: Long) -> Unit,
): DataFolderDownloadResult = withContext(Dispatchers.IO) {
    val resolver = context.applicationContext.contentResolver
    val parentDocumentUri = downloadedFileParentDocumentUri(folderTreeUri)
    val safeName = sanitizeDownloadedVideoFileName(localFileName)
    findChildDocumentUri(context, parentDocumentUri, "$safeName.tmp")?.let { tmp ->
        DocumentsContract.deleteDocument(resolver, tmp)
    }
    val tmpUri = requireNotNull(
        DocumentsContract.createDocument(
            resolver,
            parentDocumentUri,
            mimeTypeForDownloadFileName(fileName),
            "$safeName.tmp",
        ),
    ) { "无法在数据文件夹创建下载临时文件" }

    try {
        val downloadJob = currentCoroutineContext().job
        val response = client.openFullStream(remotePath) { closeable ->
            downloadJob.invokeOnCompletion { cause ->
                if (cause is kotlinx.coroutines.CancellationException) {
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
        findChildDocumentUri(context, parentDocumentUri, safeName)?.let { existing ->
            check(DocumentsContract.deleteDocument(resolver, existing)) { "无法替换已有视频文件" }
        }
        val finalUri = requireNotNull(
            DocumentsContract.renameDocument(resolver, tmpUri, safeName),
        ) { "无法保存视频文件" }
        DataFolderDownloadResult(localUri = finalUri.toString(), sizeBytes = downloaded)
    } catch (error: Throwable) {
        runCatching { DocumentsContract.deleteDocument(resolver, tmpUri) }
        throw error
    }
}

internal suspend fun downloadWebDavComicRecordToDataFolder(
    context: Context,
    folderTreeUri: Uri,
    client: WebDavClient,
    accountId: String,
    remotePath: String,
    fileName: String,
    info: RemoteFileInfo,
    downloadedAtMillis: Long,
    onProgress: (downloadedBytes: Long, totalBytes: Long) -> Unit,
): DownloadRecord {
    val result = downloadWebDavFileToDataFolder(
        context = context,
        folderTreeUri = folderTreeUri,
        client = client,
        remotePath = remotePath,
        fileName = fileName,
        localFileName = localDownloadFileNameForRemoteFile(accountId, remotePath, fileName),
        expectedSize = info.size,
        onProgress = onProgress,
    )
    return DownloadRecord(
        fileName = fileName,
        remotePath = remotePath,
        sizeBytes = result.sizeBytes,
        downloadedAtMillis = downloadedAtMillis,
        accountId = accountId,
        localUri = result.localUri,
    )
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

private const val MAX_DOWNLOAD_FILE_NAME_LENGTH = 180
private const val DOWNLOAD_FILE_NAME_HASH_BYTES = 5

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
