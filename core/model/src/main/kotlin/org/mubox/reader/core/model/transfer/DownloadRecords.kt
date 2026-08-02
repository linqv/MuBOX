package org.mubox.reader.core.model.transfer

data class DownloadRecord(
    val fileName: String,
    val remotePath: String,
    val sizeBytes: Long,
    val downloadedAtMillis: Long,
    val accountId: String? = null,
    val localUri: String? = null,
)

data class VideoDownloadRecord(
    val fileName: String,
    val accountId: String,
    val remotePath: String,
    val localUri: String,
    val sizeBytes: Long,
    val downloadedAtMillis: Long,
)
