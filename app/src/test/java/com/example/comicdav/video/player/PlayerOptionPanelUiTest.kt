package com.example.comicdav.video.player

import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Subtitles
import androidx.compose.material.icons.filled.Sync
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class PlayerOptionPanelUiTest {
    @Test
    fun sideRailPanelsUseIconsAndAccessibleLabelsInsteadOfVisibleText() {
        val descriptors = PlayerOptionPanel.entries.map { it.sideRailDescriptor() }

        assertEquals(
            listOf(
                "音轨与字幕",
                "音画同步",
                "播放信息",
                "播放队列",
            ),
            descriptors.map { it.contentDescription },
        )
        assertEquals(
            listOf(
                Icons.Filled.Subtitles,
                Icons.Filled.Sync,
                Icons.Filled.Info,
                Icons.AutoMirrored.Filled.QueueMusic,
            ),
            descriptors.map { it.icon },
        )
        assertFalse(descriptors.any { it.visibleText.isNotBlank() })
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
    fun playerControlSizingKeepsPrimaryPlaybackButtonCompact() {
        assertEquals(44, PLAYER_PRIMARY_CONTROL_TOUCH_SIZE_DP)
        assertEquals(38, PLAYER_PRIMARY_CONTROL_VISUAL_SIZE_DP)
        assertEquals(44, PLAYER_OVERLAY_BUTTON_SIZE_DP)
        assertEquals(8, PLAYER_OPTION_SHEET_RAIL_GAP_DP)
    }

    @Test
    fun controlAutoHideOptionsProvideOffAndCommonTimeouts() {
        assertEquals(
            listOf(0, 3000, 5000, 8000, 10000),
            playerControlAutoHideOptionsMillis(),
        )
    }
}
