package org.mubox.reader.video.player

import android.content.pm.ActivityInfo
import android.content.res.Configuration
import org.mubox.reader.core.model.settings.VideoPlayerOrientationMode

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

    /**
     * 退出竖屏「听视频」界面后，恢复视频画面应有的方向：
     * 优先按当前视频参数推导；被手动锁定或参数未知时回到最后一次固定的方向；
     * 传感器模式下回到传感器方向。
     */
    fun restoreOrientationAfterListenMode(videoParams: VideoParams): Int =
        requestForVideoParams(videoParams)
            ?: lastFixedOrientation
            ?: if (initialMode == VideoPlayerOrientationMode.SENSOR) {
                ActivityInfo.SCREEN_ORIENTATION_SENSOR
            } else {
                ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
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
            ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        }
    }
    val (width, height) = videoParams.displayDimensions()
    return if (width != null && height != null) {
        if (height > width) {
            ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        } else {
            ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        }
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
