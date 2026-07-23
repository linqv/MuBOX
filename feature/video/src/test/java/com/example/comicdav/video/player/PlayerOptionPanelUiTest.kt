package com.example.comicdav.video.player

import com.example.comicdav.core.model.settings.Anime4KMode
import com.example.comicdav.core.model.settings.Anime4KQuality
import com.example.comicdav.core.model.settings.playerControlAutoHideOptionsMillis
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Subtitles
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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
    fun playerExposesPreviousNextAndEpisodeSelectionControls() {
        assertEquals(
            listOf("上一集", "下一集", "选集"),
            episodeNavigationControlDescriptions(),
        )
    }

    @Test
    fun scalePanelOnlyContainsPerPlaybackVisualControls() {
        assertEquals(
            listOf("画面", "Anime4K", "预设", "质量"),
            scaleModeControlGroupLabels(),
        )
    }

    @Test
    fun anime4KControlsExposeEnablePresetAndQualityOptions() {
        assertEquals(
            listOf("关" to false, "开" to true),
            anime4kEnabledControlOptions(),
        )
        assertEquals(
            listOf("A", "B", "C", "A+", "B+", "C+"),
            Anime4KMode.entries
                .filterNot { it == Anime4KMode.OFF }
                .map(::anime4kModeControlLabel),
        )
        assertEquals(
            listOf("Fast", "Balanced", "High"),
            Anime4KQuality.entries.map(::anime4kQualityControlLabel),
        )
    }

    @Test
    fun bottomStatusPrefersPlaybackErrorsThenAnime4KStatus() {
        assertEquals(
            "播放失败",
            playerBottomStatusText(MpvPlayerState(errorMessage = "播放失败", statusMessage = "Anime4K 不兼容")),
        )
        assertEquals(
            "Anime4K 不兼容",
            playerBottomStatusText(MpvPlayerState(statusMessage = "Anime4K 不兼容")),
        )
        assertEquals(null, playerBottomStatusText(MpvPlayerState()))
    }

    @Test
    fun centerForwardSeekOnlyClampsWhenDurationIsKnown() {
        assertEquals(52_000L, seekForwardTargetMillis(positionMillis = 42_000L, durationMillis = 0L))
        assertEquals(120_000L, seekForwardTargetMillis(positionMillis = 115_000L, durationMillis = 120_000L))
    }

    @Test
    fun playerControlSizingSupportsCenterPlaybackLockAndThinProgress() {
        assertEquals(0.62f, PLAYER_TOP_BAR_MAX_WIDTH_FRACTION)
        assertEquals(0.70f, PLAYER_BOTTOM_CONTROLS_MAX_WIDTH_FRACTION)
        assertEquals(80, PLAYER_CENTER_PLAY_BUTTON_TOUCH_SIZE_DP)
        assertEquals(64, PLAYER_CENTER_PLAY_BUTTON_VISUAL_SIZE_DP)
        assertEquals(40, PLAYER_LOCK_BUTTON_SIZE_DP)
        assertEquals(18, PLAYER_LOCK_BUTTON_START_PADDING_DP)
        assertEquals(3000L, PLAYER_LOCKED_BUTTON_AUTO_HIDE_MILLIS)
        assertEquals(44, PLAYER_OVERLAY_BUTTON_SIZE_DP)
        assertEquals(3, PLAYER_PROGRESS_TRACK_HEIGHT_DP)
        assertEquals(8, PLAYER_OPTION_SHEET_RAIL_GAP_DP)
        assertEquals(6, PLAYER_BOTTOM_CONTROLS_BOTTOM_PADDING_DP)
        assertEquals(1, PLAYER_EDGE_FLOATING_CONTROLS_MAX_ITEMS)
    }

    @Test
    fun playerSizingMatchesMuBoxMediaTokens() {
        assertEquals(com.example.comicdav.ui.MuBoxMetrics.PlayerCenterControlTouchDp.value.toInt(), PLAYER_CENTER_PLAY_BUTTON_TOUCH_SIZE_DP)
        assertEquals(com.example.comicdav.ui.MuBoxMetrics.PlayerCenterControlVisualDp.value.toInt(), PLAYER_CENTER_PLAY_BUTTON_VISUAL_SIZE_DP)
        assertEquals(com.example.comicdav.ui.MuBoxMetrics.PlayerPanelCornerDp.value.toInt(), PLAYER_PANEL_CORNER_DP)
        assertEquals(com.example.comicdav.ui.MuBoxMetrics.PlayerPanelContentPaddingDp.value.toInt(), PLAYER_PANEL_CONTENT_PADDING_DP)
    }

    @Test
    fun playerGestureOverlayUsesFullScreenHitArea() {
        assertEquals(0, PLAYER_GESTURE_HORIZONTAL_PADDING_DP)
        assertEquals(0, PLAYER_GESTURE_TOP_PADDING_DP)
        assertEquals(0, PLAYER_GESTURE_END_PADDING_DP)
        assertEquals(0, PLAYER_GESTURE_BOTTOM_PADDING_DP)
    }

    @Test
    fun playerGestureRoutingKeepsHorizontalPanOutOfVerticalControls() {
        assertFalse(shouldDispatchVerticalPlayerPan(panX = 42f, panY = 6f))
        assertFalse(shouldDispatchVerticalPlayerPan(panX = 12f, panY = 12f))
        assertTrue(shouldDispatchVerticalPlayerPan(panX = 4f, panY = 28f))
    }

    @Test
    fun playerGestureDragModeSeparatesHorizontalSeekFromVerticalControls() {
        assertEquals(PlayerGestureDragMode.HORIZONTAL_SEEK, playerGestureDragModeForPan(panX = 42f, panY = 6f))
        assertEquals(PlayerGestureDragMode.VERTICAL_ADJUST, playerGestureDragModeForPan(panX = 4f, panY = 28f))
        assertEquals(null, playerGestureDragModeForPan(panX = 12f, panY = 12f))
    }

    @Test
    fun controlAutoHideOptionsProvideOffAndCommonTimeouts() {
        assertEquals(
            listOf(0, 3000, 5000, 8000, 10000),
            playerControlAutoHideOptionsMillis(),
        )
    }
}
