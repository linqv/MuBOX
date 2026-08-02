package org.mubox.reader.video.player

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import java.security.MessageDigest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

class VideoPlaybackStateStore(
    private val dataStore: DataStore<Preferences>,
) {
    suspend fun loadPosition(playbackKey: String?): Long {
        if (playbackKey.isNullOrBlank()) return 0L
        val key = positionPreferenceKey(playbackKey)
        return dataStore.data
            .map { preferences -> preferences[key] ?: 0L }
            .first()
            .coerceAtLeast(0L)
    }

    suspend fun savePosition(playbackKey: String?, positionMillis: Long, durationMillis: Long) {
        if (playbackKey.isNullOrBlank()) return
        val positionToSave = playbackPositionToSave(positionMillis, durationMillis)
        val key = positionPreferenceKey(playbackKey)
        dataStore.edit { preferences ->
            if (positionToSave > 0L) {
                preferences[key] = positionToSave
            } else {
                preferences.remove(key)
            }
        }
    }

    suspend fun clearAll() {
        dataStore.edit { preferences -> preferences.clear() }
    }

    private fun positionPreferenceKey(playbackKey: String): Preferences.Key<Long> =
        longPreferencesKey("video_position_${playbackKey.sha256Hex()}")
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

private fun String.sha256Hex(): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(toByteArray(Charsets.UTF_8))
    return digest.joinToString(separator = "") { byte -> "%02x".format(byte) }
}

private const val END_POSITION_CLEARANCE_MILLIS = 1_000L
