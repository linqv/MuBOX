package org.mubox.reader.video.player

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Coordinates resume-position loading and the lifetime of periodic progress saves for one player.
 * Persistence details remain owned by [VideoPlaybackStateStore] and [VideoPlaybackProgressSaver].
 */
internal class VideoPlaybackPersistenceCoordinator(
    private val autoSaveScope: CoroutineScope,
    private val resumeEnabled: Boolean,
    initialPlaybackKey: String?,
    private val loadPosition: suspend (String?) -> Long,
    private val savePositionAsync: (String, Long, Long) -> Unit,
    private val currentProgress: () -> VideoPlaybackProgressState,
    private val autoSaveIntervalMillis: Long = PLAYBACK_PROGRESS_SAVE_INTERVAL_MILLIS,
) {
    private var playbackKey: String? = initialPlaybackKey
    private var autoSaveJob: Job? = null

    init {
        require(autoSaveIntervalMillis > 0L) { "autoSaveIntervalMillis must be positive" }
    }

    suspend fun loadStartPosition(
        playbackKey: String? = this.playbackKey,
        onFailure: (Throwable) -> Unit = {},
    ): Long =
        loadVideoStartPosition(
            resumeEnabled = resumeEnabled,
            playbackKey = playbackKey,
            loadPosition = loadPosition,
            onFailure = onFailure,
        )

    fun adoptPlaybackKey(playbackKey: String?) {
        this.playbackKey = playbackKey
    }

    fun saveCurrentPositionAsync() {
        if (!resumeEnabled) return
        val key = playbackKey?.takeIf { it.isNotBlank() } ?: return
        val progress = currentProgress()
        savePositionAsync(key, progress.positionMillis, progress.durationMillis)
    }

    fun startAutoSave() {
        if (!resumeEnabled || autoSaveJob != null) return
        autoSaveJob = autoSaveScope.launch {
            while (true) {
                delay(autoSaveIntervalMillis)
                saveCurrentPositionAsync()
            }
        }
    }

    fun stopAutoSave() {
        autoSaveJob?.cancel()
        autoSaveJob = null
    }
}

internal suspend fun loadVideoStartPosition(
    resumeEnabled: Boolean,
    playbackKey: String?,
    loadPosition: suspend (String?) -> Long,
    onFailure: (Throwable) -> Unit = {},
): Long {
    if (!resumeEnabled) return 0L
    return runCatching {
        loadPosition(playbackKey)
    }.getOrElse { error ->
        onFailure(error)
        0L
    }.coerceAtLeast(0L)
}

private const val PLAYBACK_PROGRESS_SAVE_INTERVAL_MILLIS = 10_000L
