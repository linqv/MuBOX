package org.mubox.reader.video.player

import org.mubox.reader.core.ports.PlaybackPositionGateway
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

class VideoPlaybackStateStore(
    private val playbackPositions: PlaybackPositionGateway,
) {
    suspend fun loadPosition(playbackKey: String?): Long {
        if (playbackKey.isNullOrBlank()) return 0L
        return playbackPositions.loadPosition(playbackKey).coerceAtLeast(0L)
    }

    suspend fun savePosition(playbackKey: String?, positionMillis: Long, durationMillis: Long) {
        if (playbackKey.isNullOrBlank()) return
        val positionToSave = playbackPositionToSave(positionMillis, durationMillis)
        if (positionToSave > 0L) {
            playbackPositions.savePosition(playbackKey, positionToSave)
        } else {
            playbackPositions.deletePosition(playbackKey)
        }
    }

    suspend fun clearAll() {
        playbackPositions.clear()
    }
}

internal class VideoPlaybackProgressSaver(
    private val scope: CoroutineScope,
    private val savePosition: suspend (String, Long, Long) -> Unit,
) {
    private var latestSaveJob: Job? = null

    fun saveAsync(
        playbackKey: String?,
        positionMillis: Long,
        durationMillis: Long,
    ): Job? {
        val key = playbackKey?.takeIf { it.isNotBlank() } ?: return null
        latestSaveJob?.cancel()
        latestSaveJob = scope.launch {
            try {
                savePosition(key, positionMillis, durationMillis)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                System.err.println("Failed to save video playback position: ${error.message ?: error::class.java.simpleName}")
            }
        }
        return latestSaveJob
    }
}

fun localVideoPlaybackKey(uri: String, size: Long?, lastModified: Long?): String =
    "local|$uri|${size ?: -1L}|${lastModified ?: -1L}"

fun webDavVideoPlaybackKey(
    accountId: String,
    remotePath: String,
    size: Long?,
    etag: String?,
    lastModified: Long?,
): String =
    "webdav|$accountId|$remotePath|${size ?: -1L}|${etag.orEmpty()}|${lastModified ?: -1L}"

private fun playbackPositionToSave(positionMillis: Long, durationMillis: Long): Long {
    val safePosition = positionMillis.coerceAtLeast(0L)
    if (safePosition == 0L) return 0L
    if (durationMillis > 0L && safePosition >= durationMillis - END_POSITION_CLEARANCE_MILLIS) return 0L
    return safePosition
}

private const val END_POSITION_CLEARANCE_MILLIS = 1_000L
