package org.mubox.reader.data.playback

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert

@Dao
internal interface PlaybackPositionDao {
    @Query("SELECT positionMillis FROM playback_positions WHERE playbackKeyHash = :playbackKeyHash LIMIT 1")
    suspend fun load(playbackKeyHash: String): Long?

    @Upsert
    suspend fun upsert(position: PlaybackPositionEntity)

    @Upsert
    suspend fun upsertAll(positions: List<PlaybackPositionEntity>)

    @Query("DELETE FROM playback_positions WHERE playbackKeyHash = :playbackKeyHash")
    suspend fun delete(playbackKeyHash: String)

    @Query("DELETE FROM playback_positions")
    suspend fun clear()
}
