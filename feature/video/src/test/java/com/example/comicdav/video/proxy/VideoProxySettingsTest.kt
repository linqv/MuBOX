package com.example.comicdav.video.proxy

import com.example.comicdav.core.model.settings.VideoForwardPrefetchMode
import com.example.comicdav.core.model.settings.VideoProxyDiagnosticsMode
import com.example.comicdav.core.model.settings.VideoProxySettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoProxySettingsTest {
    @Test
    fun defaultSettingsEnableSeekOptimizationWithStandardPrefetchAndDiagnosticsOff() {
        val settings = VideoProxySettings.DEFAULT

        assertTrue(settings.seekOptimizationEnabled)
        assertEquals(VideoForwardPrefetchMode.STANDARD, settings.forwardPrefetchMode)
        assertEquals(1, settings.forwardPrefetchMode.segmentCount)
        assertEquals(VideoProxyDiagnosticsMode.OFF, settings.diagnosticsMode)
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
