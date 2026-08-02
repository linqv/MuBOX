package org.mubox.reader.video.proxy

import org.mubox.reader.core.model.settings.VideoForwardPrefetchMode
import org.mubox.reader.core.model.settings.VideoProxySettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoProxySettingsTest {
    @Test
    fun defaultSettingsEnableSeekOptimizationWithStandardPrefetch() {
        val settings = VideoProxySettings.DEFAULT

        assertTrue(settings.seekOptimizationEnabled)
        assertEquals(VideoForwardPrefetchMode.STANDARD, settings.forwardPrefetchMode)
        assertEquals(1, settings.forwardPrefetchMode.segmentCount)
    }

    @Test
    fun prefetchModesExposeSegmentCounts() {
        assertEquals(0, VideoForwardPrefetchMode.OFF.segmentCount)
        assertEquals(1, VideoForwardPrefetchMode.STANDARD.segmentCount)
        assertEquals(2, VideoForwardPrefetchMode.AGGRESSIVE.segmentCount)
    }

    @Test
    fun settingsCanDisableSeekOptimization() {
        val settings = VideoProxySettings.DEFAULT.copy(seekOptimizationEnabled = false)

        assertFalse(settings.seekOptimizationEnabled)
    }
}
