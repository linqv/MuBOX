package org.mubox.reader.core.ports

import org.mubox.reader.core.model.history.WatchHistoryEntry
import kotlinx.coroutines.flow.Flow

interface WatchHistoryGateway {
    val history: Flow<List<WatchHistoryEntry>>

    suspend fun get(mediaKey: String): WatchHistoryEntry?
    suspend fun upsert(entry: WatchHistoryEntry)
    suspend fun delete(mediaKey: String)
    suspend fun clear()

    /**
     * Removes expired entries and entries beyond [maxRecords], returning every removed entry so
     * callers can also discard media-specific caches.
     */
    suspend fun prune(
        retentionDays: Int,
        maxRecords: Int,
        nowMillis: Long = System.currentTimeMillis(),
    ): List<WatchHistoryEntry>
}
