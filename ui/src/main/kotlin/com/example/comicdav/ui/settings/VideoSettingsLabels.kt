package com.example.comicdav.ui.settings

import com.example.comicdav.core.model.settings.GpuApiMode
import com.example.comicdav.core.model.settings.MpvProfileMode
import com.example.comicdav.core.model.settings.VideoBackgroundMode
import com.example.comicdav.core.model.settings.VideoDecoderMode
import com.example.comicdav.core.model.settings.VideoOutputMode
import com.example.comicdav.core.model.settings.VideoPlayerOrientationMode

fun videoBackgroundModeLabel(mode: VideoBackgroundMode): String =
    when (mode) {
        VideoBackgroundMode.NONE -> "暂停播放（默认）"
        VideoBackgroundMode.BACKGROUND_PLAY -> "后台继续播放音频"
        VideoBackgroundMode.RESUME_ON_RETURN -> "回来时继续播放"
    }

fun videoDecoderModeLabel(mode: VideoDecoderMode): String =
    when (mode) {
        VideoDecoderMode.AUTO -> "auto"
        VideoDecoderMode.SOFTWARE -> "SW"
        VideoDecoderMode.HARDWARE -> "HW"
        VideoDecoderMode.HARDWARE_PLUS -> "HW+"
    }

fun videoOutputModeLabel(mode: VideoOutputMode): String =
    when (mode) {
        VideoOutputMode.AUTO -> "auto"
        VideoOutputMode.GPU_NEXT -> "gpu-next"
    }

fun gpuApiModeLabel(mode: GpuApiMode): String =
    when (mode) {
        GpuApiMode.AUTO -> "auto"
        GpuApiMode.VULKAN -> "vulkan"
    }

fun mpvProfileModeLabel(mode: MpvProfileMode): String =
    when (mode) {
        MpvProfileMode.FAST -> "Fast"
        MpvProfileMode.DEFAULT -> "Default"
        MpvProfileMode.HIGH_QUALITY -> "High Quality"
        MpvProfileMode.GPU_HQ -> "GPU HQ"
        MpvProfileMode.LOW_LATENCY -> "Low Latency"
        MpvProfileMode.SW_FAST -> "SW Fast"
    }

fun playerControlAutoHideLabel(millis: Int): String =
    if (millis <= 0) "不自动隐藏" else "${millis / 1_000} 秒"

fun videoPlayerOrientationModeLabel(mode: VideoPlayerOrientationMode): String =
    when (mode) {
        VideoPlayerOrientationMode.VIDEO -> "视频"
        VideoPlayerOrientationMode.PORTRAIT -> "竖屏"
        VideoPlayerOrientationMode.LANDSCAPE -> "横屏"
        VideoPlayerOrientationMode.SENSOR -> "传感器"
    }
