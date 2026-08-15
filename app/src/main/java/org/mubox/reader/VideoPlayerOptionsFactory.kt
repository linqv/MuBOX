package org.mubox.reader

import org.mubox.reader.core.model.settings.AppSettings
import org.mubox.reader.video.player.VideoPlayerOptions

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
        colorPalette = appearance.colorPalette,
    )
