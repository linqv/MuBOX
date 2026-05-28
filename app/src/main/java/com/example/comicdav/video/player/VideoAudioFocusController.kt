package com.example.comicdav.video.player

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Handler
import android.os.Looper

class VideoAudioFocusController private constructor(
    private val onFocusLost: () -> Unit,
    private val focusGateway: VideoAudioFocusGateway,
) {
    private var hasFocus = false

    constructor(
        context: Context,
        onFocusLost: () -> Unit,
    ) : this(
        onFocusLost = onFocusLost,
        focusGateway = AndroidVideoAudioFocusGateway(context.applicationContext),
    )

    internal constructor(
        context: Context,
        onFocusLost: () -> Unit,
        focusGateway: VideoAudioFocusGateway,
    ) : this(
        onFocusLost = onFocusLost,
        focusGateway = focusGateway,
    )

    private val focusListener = VideoAudioFocusListener { change ->
        when (change) {
            VideoAudioFocusChange.Loss,
            VideoAudioFocusChange.TransientLoss,
            -> onFocusLost()

            VideoAudioFocusChange.Duck,
            VideoAudioFocusChange.Gain,
            VideoAudioFocusChange.Other,
            -> Unit
        }
    }

    fun request(): Boolean {
        hasFocus = focusGateway.request(focusListener)
        return hasFocus
    }

    fun abandon() {
        if (!hasFocus) return
        hasFocus = false
        focusGateway.abandon()
    }
}

internal class VideoPlaybackLifecyclePolicy(
    private val onPausePlayback: () -> Unit,
    private val onCleanupPlayback: () -> Unit,
    private val onBackgroundTimeoutAfterCleanup: () -> Unit = {},
    private val backgroundCleanupDelayMillis: Long = DEFAULT_BACKGROUND_CLEANUP_DELAY_MILLIS,
    private val backgroundCleanupScheduler: BackgroundCleanupScheduler = MainThreadBackgroundCleanupScheduler(),
) {
    private var pausedForBackground = false
    private var cleanedUp = false
    private var backgroundCleanupHandle: BackgroundCleanupHandle? = null

    fun moveToBackground() {
        if (cleanedUp) return
        if (!pausedForBackground) {
            pausedForBackground = true
            onPausePlayback()
        }
        if (backgroundCleanupHandle == null) {
            backgroundCleanupHandle = backgroundCleanupScheduler.schedule(backgroundCleanupDelayMillis) {
                if (!cleanedUp) {
                    cleanup()
                    onBackgroundTimeoutAfterCleanup()
                }
            }
        }
    }

    fun returnToForeground() {
        if (cleanedUp) return
        // Returning to foreground keeps playback paused; the user resumes explicitly.
        pausedForBackground = false
        cancelBackgroundCleanup()
    }

    fun cleanup() {
        if (cleanedUp) return
        cleanedUp = true
        cancelBackgroundCleanup()
        onCleanupPlayback()
    }

    private fun cancelBackgroundCleanup() {
        backgroundCleanupHandle?.cancel()
        backgroundCleanupHandle = null
    }

    private companion object {
        private const val DEFAULT_BACKGROUND_CLEANUP_DELAY_MILLIS = 5L * 60L * 1000L
    }
}

internal fun interface BackgroundCleanupHandle {
    fun cancel()
}

internal interface BackgroundCleanupScheduler {
    fun schedule(delayMillis: Long, action: () -> Unit): BackgroundCleanupHandle
}

private class MainThreadBackgroundCleanupScheduler : BackgroundCleanupScheduler {
    private val handler = Handler(Looper.getMainLooper())

    override fun schedule(delayMillis: Long, action: () -> Unit): BackgroundCleanupHandle {
        val runnable = Runnable(action)
        handler.postDelayed(runnable, delayMillis)
        return BackgroundCleanupHandle {
            handler.removeCallbacks(runnable)
        }
    }
}

internal fun interface VideoAudioFocusListener {
    fun onAudioFocusChange(change: VideoAudioFocusChange)
}

internal interface VideoAudioFocusGateway {
    fun request(listener: VideoAudioFocusListener): Boolean
    fun abandon()
}

internal enum class VideoAudioFocusChange {
    Loss,
    TransientLoss,
    Duck,
    Gain,
    Other,
}

private class AndroidVideoAudioFocusGateway(
    context: Context,
) : VideoAudioFocusGateway {
    private val audioManager = context.getSystemService(AudioManager::class.java)
    private var focusRequest: AudioFocusRequest? = null

    override fun request(listener: VideoAudioFocusListener): Boolean {
        val result = requestModernFocus(listener)
        val granted = result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        if (!granted) {
            focusRequest = null
        }
        return granted
    }

    override fun abandon() {
        focusRequest?.let(audioManager::abandonAudioFocusRequest)
        focusRequest = null
    }

    private fun requestModernFocus(listener: VideoAudioFocusListener): Int {
        val attributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
            .build()
        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(attributes)
            .setOnAudioFocusChangeListener { focusChange ->
                listener.onAudioFocusChange(focusChange.toVideoAudioFocusChange())
            }
            .build()
        focusRequest = request
        return audioManager.requestAudioFocus(request)
    }

    private fun Int.toVideoAudioFocusChange(): VideoAudioFocusChange =
        when (this) {
            AudioManager.AUDIOFOCUS_LOSS -> VideoAudioFocusChange.Loss
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> VideoAudioFocusChange.TransientLoss
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> VideoAudioFocusChange.Duck
            AudioManager.AUDIOFOCUS_GAIN -> VideoAudioFocusChange.Gain
            else -> VideoAudioFocusChange.Other
        }
}
