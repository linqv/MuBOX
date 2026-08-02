package org.mubox.reader.data.history

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
internal interface WatchHistoryDao {
    @Query("SELECT * FROM watch_history ORDER BY lastWatchedAt DESC")
    fun observeAll(): Flow<List<WatchHistoryEntity>>

    @Query("SELECT * FROM watch_history WHERE mediaKey = :mediaKey LIMIT 1")
    suspend fun find(mediaKey: String): WatchHistoryEntity?

    @Query("SELECT * FROM watch_history ORDER BY lastWatchedAt DESC")
    suspend fun loadAll(): List<WatchHistoryEntity>

    @Upsert
    suspend fun upsert(entry: WatchHistoryEntity)

    @Query("DELETE FROM watch_history WHERE mediaKey = :mediaKey")
    suspend fun delete(mediaKey: String)

    @Query("DELETE FROM watch_history WHERE mediaKey IN (:mediaKeys)")
    suspend fun delete(mediaKeys: List<String>)

    @Query("DELETE FROM watch_history")
    suspend fun clear()
}
