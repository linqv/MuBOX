package org.mubox.reader.core.ports

interface PlaybackPositionGateway {
    suspend fun loadPosition(playbackKey: String): Long
    suspend fun savePosition(playbackKey: String, positionMillis: Long)
    suspend fun deletePosition(playbackKey: String)
    suspend fun clear()
}
