package com.example.comicdav

import com.example.comicdav.core.model.settings.AppSettings
import com.example.comicdav.video.player.VideoPlayerOptions

internal fun AppSettings.toVideoPlayerOptions(): VideoPlayerOptions =
    VideoPlayerOptions(
        resumeEnabled = videoResumeEnabled,
        videoOutputMode = videoOutputMode,
        gpuApiMode = gpuApiMode,
        videoDecoderMode = videoDecoderMode,
        mpvProfileMode = mpvProfileMode,
        controlsAutoHideMillis = videoControlsAutoHideMillis,
        playerOrientationMode = videoPlayerOrientationMode,
        proxyDebugInfoEnabled = videoPlayerProxyDebugInfoEnabled,
        videoBackgroundMode = videoBackgroundMode,
        anime4kProfile = anime4kProfile,
    )
