package com.example.comicdav

import android.content.Context
import android.net.Uri
import android.os.SystemClock
import android.provider.DocumentsContract
import com.example.comicdav.feature.reader.ReaderLoadingProgress
import com.example.comicdav.feature.webdav.DownloadProgressUi
import com.example.comicdav.webdav.decodeWebDavPathForDisplay
import java.io.InputStream
import java.io.OutputStream
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

internal suspend fun downloadWebDavVideoToDataFolder(
    context: Context,
    folderTreeUri: Uri,
    client: com.example.comicdav.network.WebDavClient,
    remotePath: String,
    fileName: String,
    expectedSize: Long,
    onProgress: (downloadedBytes: Long, totalBytes: Long) -> Unit,
): String = withContext(Dispatchers.IO) {
    val resolver = context.applicationContext.contentResolver
    val rootDocumentUri = DocumentsContract.buildDocumentUriUsingTree(
        folderTreeUri,
        DocumentsContract.getTreeDocumentId(folderTreeUri),
    )
    val videosDirectoryUri = findOrCreateChildDocument(
        context = context,
        parentDocumentUri = rootDocumentUri,
        displayName = "videos",
        mimeType = DocumentsContract.Document.MIME_TYPE_DIR,
    )
    val safeName = sanitizeDownloadedVideoFileName(fileName)
    findChildDocumentUri(context, videosDirectoryUri, "$safeName.tmp")?.let { tmp ->
        DocumentsContract.deleteDocument(resolver, tmp)
    }
    val tmpUri = requireNotNull(
        DocumentsContract.createDocument(
            resolver,
            videosDirectoryUri,
            com.example.comicdav.video.mimeTypeForMediaFileName(fileName) ?: "application/octet-stream",
            "$safeName.tmp",
        ),
    ) { "无法在数据文件夹创建视频临时文件" }

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
        findChildDocumentUri(context, videosDirectoryUri, safeName)?.let { existing ->
            check(DocumentsContract.deleteDocument(resolver, existing)) { "无法替换已有视频文件" }
        }
        val finalUri = requireNotNull(
            DocumentsContract.renameDocument(resolver, tmpUri, safeName),
        ) { "无法保存视频文件" }
        finalUri.toString()
    } catch (error: Throwable) {
        runCatching { DocumentsContract.deleteDocument(resolver, tmpUri) }
        throw error
    }
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
