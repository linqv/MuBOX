package org.mubox.reader.data.download

import androidx.room.Entity
import androidx.room.Index

@Entity(
    tableName = "download_records",
    primaryKeys = ["remotePath", "fileName"],
    indices = [Index(value = ["downloadedAtMillis"])],
)
internal data class DownloadRecordEntity(
    val fileName: String,
    val remotePath: String,
    val sizeBytes: Long,
    val downloadedAtMillis: Long,
    val accountId: String?,
    val localUri: String?,
)

@Entity(
    tableName = "video_download_records",
    primaryKeys = ["accountId", "remotePath"],
    indices = [Index(value = ["downloadedAtMillis"])],
)
internal data class VideoDownloadRecordEntity(
    val fileName: String,
    val accountId: String,
    val remotePath: String,
    val localUri: String,
    val sizeBytes: Long,
    val downloadedAtMillis: Long,
)
