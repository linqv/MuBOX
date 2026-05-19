package com.example.comicdav.video.player

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build

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

class VideoPlaybackLifecyclePolicy(
    private val onPausePlayback: () -> Unit,
    private val onCleanupPlayback: () -> Unit,
) {
    private var pausedForBackground = false
    private var cleanedUp = false

    fun setPausedForBackground(paused: Boolean) {
        if (cleanedUp || pausedForBackground == paused) return
        pausedForBackground = paused
        if (paused) {
            onPausePlayback()
        }
    }

    fun cleanup() {
        if (cleanedUp) return
        cleanedUp = true
        onCleanupPlayback()
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
    private var legacyListener: AudioManager.OnAudioFocusChangeListener? = null

    override fun request(listener: VideoAudioFocusListener): Boolean {
        val result = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            requestModernFocus(listener)
        } else {
            requestLegacyFocus(listener)
        }
        val granted = result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        if (!granted) {
            focusRequest = null
            legacyListener = null
        }
        return granted
    }

    override fun abandon() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            focusRequest?.let(audioManager::abandonAudioFocusRequest)
            focusRequest = null
        } else {
            @Suppress("DEPRECATION")
            legacyListener?.let(audioManager::abandonAudioFocus)
            legacyListener = null
        }
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

    @Suppress("DEPRECATION")
    private fun requestLegacyFocus(listener: VideoAudioFocusListener): Int {
        val audioFocusListener = AudioManager.OnAudioFocusChangeListener { focusChange ->
            listener.onAudioFocusChange(focusChange.toVideoAudioFocusChange())
        }
        legacyListener = audioFocusListener
        return audioManager.requestAudioFocus(
            audioFocusListener,
            AudioManager.STREAM_MUSIC,
            AudioManager.AUDIOFOCUS_GAIN,
        )
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
