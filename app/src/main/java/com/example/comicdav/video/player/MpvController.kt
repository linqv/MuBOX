package com.example.comicdav.video.player

import `is`.xyz.mpv.MPVLib
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class MpvPlayerState(
    val displayName: String = "",
    val isPaused: Boolean = false,
    val durationMillis: Long = 0L,
    val positionMillis: Long = 0L,
    val errorMessage: String? = null,
)

interface MpvEngine {
    fun loadFile(uri: String) {
        command("loadfile", uri)
    }

    fun command(vararg args: String)
    fun setPropertyString(name: String, value: String)
    fun setPropertyBoolean(name: String, value: Boolean)
    fun destroy()
}

object RealMpvEngine : MpvEngine {
    override fun command(vararg args: String) {
        MPVLib.command(*args)
    }

    override fun setPropertyString(name: String, value: String) {
        MPVLib.setPropertyString(name, value)
    }

    override fun setPropertyBoolean(name: String, value: Boolean) {
        MPVLib.setPropertyBoolean(name, value)
    }

    override fun destroy() {
        MPVLib.destroy()
    }
}

class ViewBackedMpvEngine(
    private val view: MuBoxMpvView,
) : MpvEngine {
    override fun loadFile(uri: String) {
        view.playFile(uri)
    }

    override fun command(vararg args: String) {
        MPVLib.command(*args)
    }

    override fun setPropertyString(name: String, value: String) {
        MPVLib.setPropertyString(name, value)
    }

    override fun setPropertyBoolean(name: String, value: Boolean) {
        MPVLib.setPropertyBoolean(name, value)
    }

    override fun destroy() {
        view.destroy()
    }
}

class MpvController(
    private val engine: MpvEngine,
) {
    private val _state = MutableStateFlow(MpvPlayerState())
    val state: StateFlow<MpvPlayerState> = _state.asStateFlow()
    @Volatile
    private var isCleaning = false
    @Volatile
    private var isDestroyed = false

    fun load(uri: String, displayName: String) {
        if (!canWriteEngine()) return
        _state.value = _state.value.copy(displayName = displayName, errorMessage = null)
        engine.setPropertyString("force-media-title", displayName)
        engine.loadFile(uri)
    }

    fun setPaused(paused: Boolean) {
        if (!canWriteEngine()) return
        _state.value = _state.value.copy(isPaused = paused)
        engine.setPropertyBoolean("pause", paused)
    }

    fun togglePlayPause() {
        setPaused(!_state.value.isPaused)
    }

    fun seekTo(positionMillis: Long) {
        if (!canWriteEngine()) return
        val durationMillis = _state.value.durationMillis
        val clampedPosition = when {
            durationMillis > 0L -> positionMillis.coerceIn(0L, durationMillis)
            else -> positionMillis.coerceAtLeast(0L)
        }
        _state.value = _state.value.copy(positionMillis = clampedPosition)
        engine.command("seek", (clampedPosition / 1000.0).toString(), "absolute")
    }

    fun onPauseChanged(paused: Boolean) {
        _state.value = _state.value.copy(isPaused = paused)
    }

    fun markPaused(paused: Boolean) {
        _state.value = _state.value.copy(isPaused = paused)
    }

    fun onPlaybackEnded() {
        markPaused(true)
    }

    fun onDurationChanged(durationSeconds: Double) {
        _state.value = _state.value.copy(durationMillis = secondsToMillis(durationSeconds))
    }

    fun onPositionChanged(positionSeconds: Double) {
        _state.value = _state.value.copy(positionMillis = secondsToMillis(positionSeconds))
    }

    fun onError(message: String) {
        _state.value = _state.value.copy(errorMessage = message)
    }

    fun destroy() {
        if (isDestroyed || isCleaning) return
        isCleaning = true
        val cleanupFailures = mutableListOf<Exception>()
        _state.value = _state.value.copy(isPaused = true)
        try {
            attemptCleanup(cleanupFailures) {
                engine.setPropertyBoolean("pause", true)
            }
            attemptCleanup(cleanupFailures) {
                engine.command("stop")
            }
            attemptCleanup(cleanupFailures) {
                engine.command("quit")
            }
            attemptCleanup(cleanupFailures) {
                Thread.sleep(100)
            }
        } finally {
            attemptCleanup(cleanupFailures) {
                engine.destroy()
            }
            isDestroyed = true
            isCleaning = false
            reportCleanupFailures(cleanupFailures)
        }
    }

    private fun canWriteEngine(): Boolean = !isCleaning && !isDestroyed

    private fun attemptCleanup(failures: MutableList<Exception>, block: () -> Unit) {
        try {
            block()
        } catch (exception: Exception) {
            if (exception is InterruptedException) {
                Thread.currentThread().interrupt()
            }
            failures += exception
        }
    }

    private fun reportCleanupFailures(failures: List<Exception>) {
        if (failures.isEmpty()) return

        System.err.println("MpvController destroy cleanup completed with ${failures.size} failure(s).")
        failures.forEach { failure ->
            failure.printStackTrace()
        }
    }

    private fun secondsToMillis(seconds: Double): Long =
        (seconds.coerceAtLeast(0.0) * 1000).toLong()
}
