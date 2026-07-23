package com.example.comicdav

import com.example.comicdav.core.model.settings.Anime4KMode
import com.example.comicdav.core.model.settings.Anime4KQuality
import com.example.comicdav.core.model.settings.AppSettings
import com.example.comicdav.core.model.settings.GpuApiMode
import com.example.comicdav.core.model.settings.MpvProfileMode
import com.example.comicdav.core.model.settings.VideoBackgroundMode
import com.example.comicdav.core.model.settings.VideoDecoderMode
import com.example.comicdav.core.model.settings.VideoOutputMode
import com.example.comicdav.core.model.settings.VideoPlayerOrientationMode
import com.example.comicdav.video.player.VideoPlayerOptions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class VideoPlayerOptionsFactoryTest {
    @Test
    fun appSettingsDefaultToAutomaticVideoBackendModes() {
        val settings = AppSettings()

        assertEquals(VideoOutputMode.AUTO, settings.videoOutputMode)
        assertEquals(GpuApiMode.AUTO, settings.gpuApiMode)
        assertEquals(VideoDecoderMode.AUTO, settings.videoDecoderMode)
        assertEquals(MpvProfileMode.FAST, settings.mpvProfileMode)
        assertEquals(5000, settings.videoControlsAutoHideMillis)
        assertFalse(settings.videoPlayerProxyDebugInfoEnabled)
        assertEquals(VideoPlayerOptions(), settings.toVideoPlayerOptions())
    }

    @Test
    fun appSettingsMapEveryPlaybackOptionIntoOneValue() {
        val options = AppSettings(
            videoResumeEnabled = false,
            videoOutputMode = VideoOutputMode.GPU_NEXT,
            gpuApiMode = GpuApiMode.VULKAN,
            videoDecoderMode = VideoDecoderMode.HARDWARE_PLUS,
            mpvProfileMode = MpvProfileMode.LOW_LATENCY,
            videoControlsAutoHideMillis = 8_000,
            videoPlayerOrientationMode = VideoPlayerOrientationMode.SENSOR,
            videoPlayerProxyDebugInfoEnabled = true,
            videoBackgroundMode = VideoBackgroundMode.BACKGROUND_PLAY,
            anime4kEnabled = true,
            anime4kMode = Anime4KMode.C_PLUS,
            anime4kQuality = Anime4KQuality.HIGH,
        ).toVideoPlayerOptions()

        assertEquals(
            VideoPlayerOptions(
                resumeEnabled = false,
                videoOutputMode = VideoOutputMode.GPU_NEXT,
                gpuApiMode = GpuApiMode.VULKAN,
                videoDecoderMode = VideoDecoderMode.HARDWARE_PLUS,
                mpvProfileMode = MpvProfileMode.LOW_LATENCY,
                controlsAutoHideMillis = 8_000,
                playerOrientationMode = VideoPlayerOrientationMode.SENSOR,
                proxyDebugInfoEnabled = true,
                videoBackgroundMode = VideoBackgroundMode.BACKGROUND_PLAY,
                anime4kEnabled = true,
                anime4kMode = Anime4KMode.C_PLUS,
                anime4kQuality = Anime4KQuality.HIGH,
            ),
            options,
        )
    }
}
