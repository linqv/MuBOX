package com.example.comicdav.data.history

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "watch_history")
internal data class WatchHistoryEntity(
    @PrimaryKey val mediaKey: String,
    val mediaType: String,
    val title: String,
    val sourceType: String,
    val sourceLocator: String,
    val accountId: String?,
    val size: Long?,
    val etag: String?,
    val lastModified: Long?,
    val progress: Long,
    val total: Long,
    val lastWatchedAt: Long,
)
