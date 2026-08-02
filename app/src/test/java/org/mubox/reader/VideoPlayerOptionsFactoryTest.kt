package org.mubox.reader

import org.mubox.reader.core.model.settings.Anime4KProfile
import org.mubox.reader.core.model.settings.AppSettings
import org.mubox.reader.core.model.settings.GpuApiMode
import org.mubox.reader.core.model.settings.MpvProfileMode
import org.mubox.reader.core.model.settings.VideoBackgroundMode
import org.mubox.reader.core.model.settings.VideoDecoderMode
import org.mubox.reader.core.model.settings.VideoOutputMode
import org.mubox.reader.core.model.settings.VideoPlayerOrientationMode
import org.mubox.reader.core.model.settings.VideoSettings
import org.mubox.reader.video.player.VideoPlayerOptions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class VideoPlayerOptionsFactoryTest {
    @Test
    fun appSettingsDefaultToAutomaticVideoBackendModes() {
        val settings = AppSettings()

        assertEquals(VideoOutputMode.AUTO, settings.video.videoOutputMode)
        assertEquals(GpuApiMode.AUTO, settings.video.gpuApiMode)
        assertEquals(VideoDecoderMode.AUTO, settings.video.videoDecoderMode)
        assertEquals(MpvProfileMode.FAST, settings.video.mpvProfileMode)
        assertEquals(5000, settings.video.videoControlsAutoHideMillis)
        assertFalse(settings.video.videoPlayerProxyDebugInfoEnabled)
        assertEquals(VideoPlayerOptions(), settings.toVideoPlayerOptions())
    }

    @Test
    fun appSettingsMapEveryPlaybackOptionIntoOneValue() {
        val options = AppSettings(
            video = VideoSettings(
                videoResumeEnabled = false,
                videoOutputMode = VideoOutputMode.GPU_NEXT,
                gpuApiMode = GpuApiMode.VULKAN,
                videoDecoderMode = VideoDecoderMode.HARDWARE_PLUS,
                mpvProfileMode = MpvProfileMode.LOW_LATENCY,
                videoControlsAutoHideMillis = 8_000,
                videoPlayerOrientationMode = VideoPlayerOrientationMode.SENSOR,
                videoPlayerProxyDebugInfoEnabled = true,
                videoBackgroundMode = VideoBackgroundMode.BACKGROUND_PLAY,
                anime4kProfile = Anime4KProfile.EXTREME,
            ),
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
                anime4kProfile = Anime4KProfile.EXTREME,
            ),
            options,
        )
    }
}
