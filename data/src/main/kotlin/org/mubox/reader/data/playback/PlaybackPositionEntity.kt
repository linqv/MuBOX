package org.mubox.reader.data.playback

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "playback_positions")
internal data class PlaybackPositionEntity(
    @PrimaryKey val playbackKeyHash: String,
    val positionMillis: Long,
)
