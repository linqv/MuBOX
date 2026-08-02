package com.example.comicdav

import com.example.comicdav.core.model.settings.AppSettings
import com.example.comicdav.video.player.VideoPlayerOptions

internal fun AppSettings.toVideoPlayerOptions(): VideoPlayerOptions =
    VideoPlayerOptions(
        resumeEnabled = video.videoResumeEnabled,
        videoOutputMode = video.videoOutputMode,
        gpuApiMode = video.gpuApiMode,
        videoDecoderMode = video.videoDecoderMode,
        mpvProfileMode = video.mpvProfileMode,
        controlsAutoHideMillis = video.videoControlsAutoHideMillis,
        playerOrientationMode = video.videoPlayerOrientationMode,
        proxyDebugInfoEnabled = video.videoPlayerProxyDebugInfoEnabled,
        videoBackgroundMode = video.videoBackgroundMode,
        anime4kProfile = video.anime4kProfile,
    )
