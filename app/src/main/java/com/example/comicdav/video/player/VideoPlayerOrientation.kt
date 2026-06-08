package com.example.comicdav.video.player

import android.content.pm.ActivityInfo
import android.content.res.Configuration

enum class VideoPlayerOrientationMode {
    VIDEO,
    PORTRAIT,
    LANDSCAPE,
    SENSOR,
}

enum class VideoBackgroundMode {
    NONE,
    BACKGROUND_PLAY,
    RESUME_ON_RETURN,
}

internal fun videoBackgroundModeLabel(mode: VideoBackgroundMode): String =
    when (mode) {
        VideoBackgroundMode.NONE -> "暂停播放（默认）"
        VideoBackgroundMode.BACKGROUND_PLAY -> "后台继续播放音频"
        VideoBackgroundMode.RESUME_ON_RETURN -> "回来时继续播放"
    }

internal fun videoPlayerOrientationModeLabel(mode: VideoPlayerOrientationMode): String =
    when (mode) {
        VideoPlayerOrientationMode.VIDEO -> "视频"
        VideoPlayerOrientationMode.PORTRAIT -> "竖屏"
        VideoPlayerOrientationMode.LANDSCAPE -> "横屏"
        VideoPlayerOrientationMode.SENSOR -> "传感器"
    }

internal fun requestedOrientationForVideoPlayerMode(
    mode: VideoPlayerOrientationMode,
    videoParams: VideoParams,
): Int =
    when (mode) {
        VideoPlayerOrientationMode.VIDEO -> requestedOrientationForVideoParams(videoParams)
        VideoPlayerOrientationMode.PORTRAIT -> ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        VideoPlayerOrientationMode.LANDSCAPE -> ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        VideoPlayerOrientationMode.SENSOR -> ActivityInfo.SCREEN_ORIENTATION_SENSOR
    }

internal fun preferredVideoParamsForOrientation(state: MpvPlayerState): VideoParams =
    state.videoOutParams.takeIf { it.hasOrientationSignal } ?: state.videoParams

internal class VideoPlayerOrientationSession(
    private val initialMode: VideoPlayerOrientationMode,
) {
    private var manualOverride = false
    private var lastFixedOrientation: Int? = null

    fun initialRequestedOrientation(): Int =
        requestedOrientationForVideoPlayerMode(initialMode, VideoParams())
            .also(::rememberFixedOrientation)

    fun requestForVideoParams(videoParams: VideoParams): Int? {
        if (initialMode != VideoPlayerOrientationMode.VIDEO || manualOverride) return null
        if (!videoParams.hasOrientationSignal) return null
        return requestedOrientationForVideoParams(videoParams)
            .also(::rememberFixedOrientation)
    }

    fun toggleFixedOrientation(currentConfigurationOrientation: Int): Int {
        manualOverride = true
        val currentFixed = lastFixedOrientation ?: fixedOrientationForConfiguration(currentConfigurationOrientation)
        val next = if (currentFixed == ActivityInfo.SCREEN_ORIENTATION_PORTRAIT) {
            ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        } else {
            ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        }
        lastFixedOrientation = next
        return next
    }

    private fun rememberFixedOrientation(requestedOrientation: Int) {
        if (
            requestedOrientation == ActivityInfo.SCREEN_ORIENTATION_PORTRAIT ||
            requestedOrientation == ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        ) {
            lastFixedOrientation = requestedOrientation
        }
    }
}

private fun requestedOrientationForVideoParams(videoParams: VideoParams): Int {
    videoParams.displayAspectRatio()?.let { aspectRatio ->
        return if (aspectRatio < 1.0) {
            ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        } else {
            ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        }
    }
    val (width, height) = videoParams.displayDimensions()
    return if (width != null && height != null && height > width) {
        ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
    } else {
        ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
    }
}

private val VideoParams.hasOrientationSignal: Boolean
    get() = displayAspectRatio() != null || (width != null && height != null)

private fun VideoParams.displayAspectRatio(): Double? {
    val aspectRatio = aspectRatio?.takeIf { it > 0.0 && !it.isNaN() && !it.isInfinite() } ?: return null
    return if (rotationDegrees?.floorMod360() in setOf(90, 270)) {
        1.0 / aspectRatio
    } else {
        aspectRatio
    }
}

private fun VideoParams.displayDimensions(): Pair<Int?, Int?> {
    val width = width
    val height = height
    if (width == null || height == null) return width to height
    return if (rotationDegrees?.floorMod360() in setOf(90, 270)) {
        height to width
    } else {
        width to height
    }
}

private fun Int.floorMod360(): Int =
    ((this % 360) + 360) % 360

private fun fixedOrientationForConfiguration(configurationOrientation: Int): Int =
    if (configurationOrientation == Configuration.ORIENTATION_PORTRAIT) {
        ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
    } else {
        ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
    }
