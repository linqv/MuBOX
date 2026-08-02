package org.mubox.reader.core.model.transfer

data class TransferProgress(
    val downloadedBytes: Long,
    val totalBytes: Long,
) {
    val fraction: Float
        get() = if (totalBytes <= 0L) 0f else (downloadedBytes.toFloat() / totalBytes).coerceIn(0f, 1f)
}

enum class DownloadMediaType {
    COMIC,
    VIDEO,
}

enum class DownloadOrigin {
    WEB_DAV_BROWSER,
    LIBRARY,
}

data class ComicDownloadRequest(
    val folderUri: String,
    val accountId: String,
    val remotePath: String,
    val fileName: String,
    val size: Long?,
    val etag: String?,
    val lastModified: Long?,
    val origin: DownloadOrigin,
)

data class VideoDownloadRequest(
    val folderUri: String,
    val accountId: String,
    val remotePath: String,
    val fileName: String,
    val size: Long?,
    val etag: String?,
    val lastModified: Long?,
    val origin: DownloadOrigin = DownloadOrigin.WEB_DAV_BROWSER,
)

data class DownloadTask(
    val id: Long,
    val fileName: String,
    val remotePath: String,
    val mediaType: DownloadMediaType,
    val origin: DownloadOrigin,
    val totalBytes: Long?,
)
