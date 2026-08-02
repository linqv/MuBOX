package org.mubox.reader.data.playback

import java.security.MessageDigest
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

internal fun String.sha256Hex(): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(toByteArray(Charsets.UTF_8))
    return digest.joinToString(separator = "") { byte -> "%02x".format(byte) }
}
