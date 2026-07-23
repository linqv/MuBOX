package com.example.comicdav.feature.downloads

import android.content.Context
import android.net.Uri
import android.os.SystemClock
import android.provider.DocumentsContract
import com.example.comicdav.data.DownloadRecord
import com.example.comicdav.core.remote.RemoteFileInfo
import com.example.comicdav.core.remote.WebDavClient
import java.io.Closeable
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
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
    com.example.comicdav.core.model.media.mimeTypeForMediaFileName(fileName)
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
    client: com.example.comicdav.core.remote.WebDavClient,
    accountId: String,
    remotePath: String,
    fileName: String,
    expectedSize: Long,
    registerCancellation: (Closeable) -> Unit = {},
    onProgress: (downloadedBytes: Long, totalBytes: Long) -> Unit,
    onCommitted: suspend (DataFolderDownloadResult) -> Unit = {},
): String =
    downloadWebDavFileToDataFolder(
        context = context,
        folderTreeUri = folderTreeUri,
        client = client,
        remotePath = remotePath,
        fileName = fileName,
        localFileName = localDownloadFileNameForRemoteFile(accountId, remotePath, fileName),
        expectedSize = expectedSize,
        registerCancellation = registerCancellation,
        onProgress = onProgress,
        onCommitted = onCommitted,
    ).localUri

internal suspend fun downloadWebDavFileToDataFolder(
    context: Context,
    folderTreeUri: Uri,
    client: WebDavClient,
    remotePath: String,
    fileName: String,
    localFileName: String = fileName,
    expectedSize: Long,
    registerCancellation: (Closeable) -> Unit = {},
    onProgress: (downloadedBytes: Long, totalBytes: Long) -> Unit,
    onCommitted: suspend (DataFolderDownloadResult) -> Unit = {},
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

    var finalUri: Uri? = null
    var metadataCommitted = false
    try {
        val response = client.openFullStream(remotePath, registerCancellation)
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
        // Once all bytes have been validated, file publication and its metadata record form one
        // non-cancellable commit phase. This prevents a cancellation between rename and DataStore
        // update from leaving an unindexed final file.
        withContext(NonCancellable) {
            findChildDocumentUri(context, parentDocumentUri, safeName)?.let { existing ->
                check(DocumentsContract.deleteDocument(resolver, existing)) { "无法替换已有视频文件" }
            }
            finalUri = requireNotNull(
                DocumentsContract.renameDocument(resolver, tmpUri, safeName),
            ) { "无法保存视频文件" }
            val result = DataFolderDownloadResult(
                localUri = checkNotNull(finalUri).toString(),
                sizeBytes = downloaded,
            )
            onCommitted(result)
            metadataCommitted = true
            result
        }
    } catch (error: Throwable) {
        if (!metadataCommitted) {
            runCatching { DocumentsContract.deleteDocument(resolver, finalUri ?: tmpUri) }
        }
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
    registerCancellation: (Closeable) -> Unit = {},
    onProgress: (downloadedBytes: Long, totalBytes: Long) -> Unit,
    onCommitted: suspend (DownloadRecord) -> Unit = {},
): DownloadRecord {
    var committedRecord: DownloadRecord? = null
    val result = downloadWebDavFileToDataFolder(
        context = context,
        folderTreeUri = folderTreeUri,
        client = client,
        remotePath = remotePath,
        fileName = fileName,
        localFileName = localDownloadFileNameForRemoteFile(accountId, remotePath, fileName),
        expectedSize = info.size,
        registerCancellation = registerCancellation,
        onProgress = onProgress,
        onCommitted = { downloadResult ->
            val record = DownloadRecord(
                fileName = fileName,
                remotePath = remotePath,
                sizeBytes = downloadResult.sizeBytes,
                downloadedAtMillis = downloadedAtMillis,
                accountId = accountId,
                localUri = downloadResult.localUri,
            )
            onCommitted(record)
            committedRecord = record
        },
    )
    return committedRecord ?: DownloadRecord(
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
