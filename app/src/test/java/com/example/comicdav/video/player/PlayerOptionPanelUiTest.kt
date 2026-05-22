package com.example.comicdav.video.player

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Subtitles
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class PlayerOptionPanelUiTest {
    @Test
    fun sideRailPanelsOnlyExposeTracksAndInfoControls() {
        val descriptors = PlayerOptionPanel.entries.map { it.sideRailDescriptor() }

        assertEquals(
            listOf(
                "音轨与字幕",
                "播放信息",
            ),
            descriptors.map { it.contentDescription },
        )
        assertEquals(
            listOf(
                Icons.Filled.Subtitles,
                Icons.Filled.Info,
            ),
            descriptors.map { it.icon },
        )
        assertFalse(descriptors.any { it.visibleText.isNotBlank() })
    }

    @Test
    fun rightSideControlsIncludeOrientationBeforePanels() {
        assertEquals(
            listOf("切换横竖屏", "音轨与字幕", "播放信息"),
            rightSideControlDescriptions(),
        )
    }

    @Test
    fun bottomQuickControlsExposeSpeedScaleAndDecoder() {
        assertEquals(
            listOf("倍速", "画面", "解码"),
            bottomQuickControlLabels(),
        )
    }

    @Test
    fun scalePanelOnlyContainsPerPlaybackVisualControls() {
        assertEquals(
            listOf("画面"),
            scaleModeControlGroupLabels(),
        )
    }

    @Test
    fun playerControlSizingSupportsCenterPlaybackLockAndThinProgress() {
        assertEquals(72, PLAYER_CENTER_PLAY_BUTTON_TOUCH_SIZE_DP)
        assertEquals(56, PLAYER_CENTER_PLAY_BUTTON_VISUAL_SIZE_DP)
        assertEquals(36, PLAYER_LOCK_BUTTON_SIZE_DP)
        assertEquals(18, PLAYER_LOCK_BUTTON_START_PADDING_DP)
        assertEquals(3000L, PLAYER_LOCKED_BUTTON_AUTO_HIDE_MILLIS)
        assertEquals(44, PLAYER_OVERLAY_BUTTON_SIZE_DP)
        assertEquals(2, PLAYER_PROGRESS_TRACK_HEIGHT_DP)
        assertEquals(8, PLAYER_OPTION_SHEET_RAIL_GAP_DP)
        assertEquals(3, PLAYER_BOTTOM_CONTROLS_BOTTOM_PADDING_DP)
        assertEquals(1, PLAYER_EDGE_FLOATING_CONTROLS_MAX_ITEMS)
    }

    @Test
    fun controlAutoHideOptionsProvideOffAndCommonTimeouts() {
        assertEquals(
            listOf(0, 3000, 5000, 8000, 10000),
            playerControlAutoHideOptionsMillis(),
        )
    }
}
