package org.mubox.reader.ui.settings

import org.mubox.reader.core.model.settings.GpuApiMode
import org.mubox.reader.core.model.settings.MpvProfileMode
import org.mubox.reader.core.model.settings.VideoBackgroundMode
import org.mubox.reader.core.model.settings.VideoDecoderMode
import org.mubox.reader.core.model.settings.VideoOutputMode
import org.mubox.reader.core.model.settings.VideoPlayerOrientationMode
import org.junit.Assert.assertEquals
import org.junit.Test

class VideoSettingsLabelsTest {
    @Test
    fun labelsRemainStableAcrossSettingsAndPlayerSurfaces() {
        assertEquals(
            listOf("暂停播放（默认）", "后台继续播放音频", "回来时继续播放"),
            VideoBackgroundMode.entries.map(::videoBackgroundModeLabel),
        )
        assertEquals(
            listOf("auto", "SW", "HW", "HW+"),
            VideoDecoderMode.entries.map(::videoDecoderModeLabel),
        )
        assertEquals(
            listOf("auto", "gpu-next"),
            VideoOutputMode.entries.map(::videoOutputModeLabel),
        )
        assertEquals(
            listOf("auto", "vulkan"),
            GpuApiMode.entries.map(::gpuApiModeLabel),
        )
        assertEquals(
            listOf("Fast", "Default", "High Quality", "GPU HQ", "Low Latency", "SW Fast"),
            MpvProfileMode.entries.map(::mpvProfileModeLabel),
        )
        assertEquals(
            listOf("视频", "竖屏", "横屏", "传感器"),
            VideoPlayerOrientationMode.entries.map(::videoPlayerOrientationModeLabel),
        )
        assertEquals("不自动隐藏", playerControlAutoHideLabel(0))
        assertEquals("5 秒", playerControlAutoHideLabel(5_000))
    }
}
