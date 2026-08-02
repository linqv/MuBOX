package org.mubox.reader.data.playback

import org.mubox.reader.core.crypto.sha256Hex
import org.mubox.reader.core.ports.PlaybackPositionGateway

internal class PlaybackPositionRepository(
    private val dao: PlaybackPositionDao,
) : PlaybackPositionGateway {
    override suspend fun loadPosition(playbackKey: String): Long {
        return dao.load(playbackKey.sha256Hex())?.coerceAtLeast(0L) ?: 0L
    }

    override suspend fun savePosition(playbackKey: String, positionMillis: Long) {
        dao.upsert(
            PlaybackPositionEntity(
                playbackKeyHash = playbackKey.sha256Hex(),
                positionMillis = positionMillis.coerceAtLeast(0L),
            ),
        )
    }

    override suspend fun deletePosition(playbackKey: String) {
        dao.delete(playbackKey.sha256Hex())
    }

    override suspend fun clear() {
        dao.clear()
    }
}
