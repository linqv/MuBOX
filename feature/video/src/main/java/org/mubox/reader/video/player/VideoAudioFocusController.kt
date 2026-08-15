package org.mubox.reader.video.player

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import org.mubox.reader.core.model.settings.VideoBackgroundMode

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
            // Duck is handled by the system (automatic ducking); Gain needs no action
            // because transient loss already pauses and the user resumes explicitly.
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
    private val mode: VideoBackgroundMode = VideoBackgroundMode.NONE,
    private val isCurrentlyPlaying: () -> Boolean = { true },
    private val onPausePlayback: () -> Unit,
    private val onResumePlayback: () -> Unit = {},
    private val onCleanupPlayback: () -> Unit,
    private val onBackgroundTimeoutAfterCleanup: () -> Unit = {},
    private val onStartForegroundPlayback: () -> Boolean = { true },
    private val onStopForegroundPlayback: () -> Unit = {},
    private val isBackgroundPlayEligible: () -> Boolean = { false },
    private val backgroundCleanupDelayMillis: Long = DEFAULT_BACKGROUND_CLEANUP_DELAY_MILLIS,
    private val backgroundCleanupScheduler: BackgroundCleanupScheduler = MainThreadBackgroundCleanupScheduler(),
) {
    private var shouldResumeOnReturn = false
    private var cleanedUp = false
    private var isInBackground = false
    private var backgroundCleanupHandle: BackgroundCleanupHandle? = null

    fun moveToBackground() {
        if (cleanedUp) return
        if (isInBackground) return
        isInBackground = true
        val wasPlaying = isCurrentlyPlaying()
        when (effectiveMode()) {
            VideoBackgroundMode.NONE -> {
                shouldResumeOnReturn = false
                onPausePlayback()
                scheduleBackgroundCleanup()
            }
            VideoBackgroundMode.BACKGROUND_PLAY -> {
                if (wasPlaying) {
                    if (!onStartForegroundPlayback()) {
                        onPausePlayback()
                        scheduleBackgroundCleanup()
                    }
                } else {
                    onPausePlayback()
                    scheduleBackgroundCleanup()
                }
            }
            VideoBackgroundMode.RESUME_ON_RETURN -> {
                shouldResumeOnReturn = wasPlaying
                onPausePlayback()
                scheduleBackgroundCleanup()
            }
        }
    }

    fun returnToForeground() {
        if (cleanedUp) return
        if (!isInBackground) return
        isInBackground = false
        when (effectiveMode()) {
            VideoBackgroundMode.NONE -> {
                shouldResumeOnReturn = false
                cancelBackgroundCleanup()
            }
            VideoBackgroundMode.BACKGROUND_PLAY -> {
                cancelBackgroundCleanup()
                onStopForegroundPlayback()
            }
            VideoBackgroundMode.RESUME_ON_RETURN -> {
                val resume = shouldResumeOnReturn
                shouldResumeOnReturn = false
                cancelBackgroundCleanup()
                if (resume) onResumePlayback()
            }
        }
    }

    fun playbackEnded() {
        if (cleanedUp) return
        shouldResumeOnReturn = false
        cancelBackgroundCleanup()
        if (effectiveMode() == VideoBackgroundMode.BACKGROUND_PLAY && isInBackground) {
            cleanup()
            onBackgroundTimeoutAfterCleanup()
            return
        }
        onStopForegroundPlayback()
    }

    fun playbackInterrupted() {
        if (cleanedUp) return
        shouldResumeOnReturn = false
        if (effectiveMode() == VideoBackgroundMode.BACKGROUND_PLAY && isInBackground) {
            cancelBackgroundCleanup()
            cleanup()
            onBackgroundTimeoutAfterCleanup()
        }
    }

    /**
     * 听视频（仅音频）播放视为后台播放条件成立：即使用户没有在设置中开启后台播放
     * （NONE），或者只选择了“回来时继续播放”（RESUME_ON_RETURN），仅音频模式下
     * 退到后台也继续播放，锁屏即可继续收听。
     */
    private fun effectiveMode(): VideoBackgroundMode =
        if (isBackgroundPlayEligible()) {
            VideoBackgroundMode.BACKGROUND_PLAY
        } else {
            mode
        }

    fun cleanup() {
        if (cleanedUp) return
        cleanedUp = true
        isInBackground = false
        cancelBackgroundCleanup()
        onStopForegroundPlayback()
        onCleanupPlayback()
    }

    private fun scheduleBackgroundCleanup() {
        if (backgroundCleanupHandle == null) {
            backgroundCleanupHandle = backgroundCleanupScheduler.schedule(backgroundCleanupDelayMillis) {
                if (!cleanedUp) {
                    cleanup()
                    onBackgroundTimeoutAfterCleanup()
                }
            }
        }
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
            // Keep willPauseWhenDucked=false so the system performs automatic ducking
            // (API 26+, non-speech content). The Duck callback is intentionally not used.
            .setWillPauseWhenDucked(false)
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
