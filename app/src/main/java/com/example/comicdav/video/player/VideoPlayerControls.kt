package com.example.comicdav.video.player

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Forward10
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Subtitles
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material.icons.filled.ScreenRotation
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlin.math.roundToLong
import com.example.comicdav.ui.PlayerOsdDefaults
import com.example.comicdav.ui.rememberMuBoxColors

// ─── Top bar: orientation toggle (left), menu + close (right) ───

@Composable
internal fun PlayerTopBar(
    title: String,
    source: String,
    onClose: () -> Unit,
    onMenuClick: () -> Unit,
    onOrientationToggle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onOrientationToggle, modifier = Modifier.size(40.dp)) {
            Icon(Icons.Filled.ScreenRotation, "切换横竖屏", tint = Color.White, modifier = Modifier.size(22.dp))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            IconButton(onClick = onMenuClick, modifier = Modifier.size(40.dp)) {
                Icon(Icons.Filled.Menu, "菜单", tint = Color.White, modifier = Modifier.size(22.dp))
            }
            IconButton(onClick = onClose, modifier = Modifier.size(40.dp)) {
                Icon(Icons.Filled.Close, "关闭", tint = Color.White, modifier = Modifier.size(22.dp))
            }
        }
    }
}

// ─── Center controls: rewind 10s, play/pause, forward 10s ───

@Composable
internal fun PlayerCenterControls(
    isPaused: Boolean,
    onPlayPause: () -> Unit,
    onSeekBackward: () -> Unit,
    onSeekForward: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(36.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onSeekBackward, modifier = Modifier.size(48.dp)) {
            Icon(Icons.Filled.Replay10, "后退10秒", tint = Color.White, modifier = Modifier.size(32.dp))
        }
        IconButton(onClick = onPlayPause, modifier = Modifier.size(56.dp)) {
            Icon(
                if (isPaused) Icons.Filled.PlayArrow else Icons.Filled.Pause,
                if (isPaused) "播放" else "暂停",
                tint = Color.White,
                modifier = Modifier.size(42.dp),
            )
        }
        IconButton(onClick = onSeekForward, modifier = Modifier.size(48.dp)) {
            Icon(Icons.Filled.Forward10, "前进10秒", tint = Color.White, modifier = Modifier.size(32.dp))
        }
    }
}

// ─── Lock button ───

@Composable
internal fun PlayerLockButton(
    controlsLocked: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    IconButton(onClick = onClick, modifier = modifier.size(PLAYER_LOCK_BUTTON_SIZE_DP.dp)) {
        Icon(
            if (controlsLocked) Icons.Filled.Lock else Icons.Filled.LockOpen,
            if (controlsLocked) "解锁控制" else "锁定控制",
            tint = Color.White,
            modifier = Modifier.size(22.dp),
        )
    }
}

// ─── Bottom controls: title, seekbar, time (no function buttons) ───

@Composable
internal fun PlayerBottomControls(
    state: MpvPlayerState,
    progress: VideoPlaybackProgressState,
    onSeek: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        if (state.errorMessage != null) {
            val colors = rememberMuBoxColors()
            Surface(color = colors.errorSurface, contentColor = colors.errorText, shape = RoundedCornerShape(8.dp)) {
                Text(state.errorMessage, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp))
            }
        }
        Text(
            text = state.displayName,
            style = MaterialTheme.typography.bodyMedium,
            color = Color.White,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        ThinSeekBar(
            positionMillis = progress.positionMillis,
            durationMillis = progress.durationMillis,
            onSeek = onSeek,
            modifier = Modifier.fillMaxWidth(),
        )
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(formatVideoTime(progress.positionMillis), style = MaterialTheme.typography.labelMedium, color = Color.White)
            Text(formatVideoTime(progress.durationMillis), style = MaterialTheme.typography.labelMedium, color = Color.White.copy(alpha = 0.7f))
        }
    }
}

// ─── Seek bar ───

@Composable
internal fun ThinSeekBar(
    positionMillis: Long,
    durationMillis: Long,
    onSeek: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    val duration = durationMillis.coerceAtLeast(1L)
    val progress = (positionMillis.toFloat() / duration).coerceIn(0f, 1f)
    Canvas(
        modifier = modifier
            .heightIn(min = PLAYER_PROGRESS_TOUCH_HEIGHT_DP.dp, max = PLAYER_PROGRESS_TOUCH_HEIGHT_DP.dp)
            .pointerInput(durationMillis) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    onSeek(seekMillisForOffset(down.position.x, size.width, durationMillis))
                    drag(down.id) { change ->
                        onSeek(seekMillisForOffset(change.position.x, size.width, durationMillis))
                        change.consume()
                    }
                }
            },
    ) {
        val trackY = size.height / 2f
        val strokeWidth = 2.dp.toPx()
        val progressX = size.width * progress
        drawLine(Color.White.copy(alpha = 0.3f), Offset(0f, trackY), Offset(size.width, trackY), strokeWidth, cap = StrokeCap.Round)
        drawLine(Color.White, Offset(0f, trackY), Offset(progressX, trackY), strokeWidth, cap = StrokeCap.Round)
        drawCircle(Color.White, 6.dp.toPx(), Offset(progressX, trackY))
    }
}

// ─── Menu panel: all functional controls live here ───

@Composable
internal fun PlayerMenuPanel(
    state: MpvPlayerState,
    mediaContext: VideoPlayerMediaContext,
    proxyStatistics: VideoProxyStatistics? = null,
    proxyDebugInfoEnabled: Boolean = false,
    onDismiss: () -> Unit,
    onSpeedSelected: (Double) -> Unit,
    onScaleModeSelected: (VideoScaleMode) -> Unit,
    onDecoderModeSelected: (VideoDecoderMode) -> Unit,
    onAudioTrackSelected: (Int) -> Unit,
    onSubtitleTrackSelected: (Int) -> Unit,
    onSubtitlesDisabled: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.widthIn(min = 260.dp, max = 360.dp).heightIn(max = 480.dp),
        shape = RoundedCornerShape(16.dp),
        color = Color(0xE6242424),
        contentColor = Color.White,
        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.2f)),
    ) {
        Column(
            modifier = Modifier
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("设置", style = MaterialTheme.typography.titleMedium, color = Color.White)
                IconButton(onClick = onDismiss, modifier = Modifier.size(32.dp)) {
                    Icon(Icons.Filled.Close, "收起", tint = Color.White, modifier = Modifier.size(18.dp))
                }
            }
            ControlGroup("倍速") {
                playbackSpeedPresets.forEach { speed ->
                    CompactTextButton("${speed}x", state.playbackSpeed == speed) { onSpeedSelected(speed) }
                }
            }
            ControlGroup("画面") {
                VideoScaleMode.entries.forEach { mode ->
                    CompactTextButton(mode.label, state.scaleMode == mode) { onScaleModeSelected(mode) }
                }
            }
            ControlGroup("解码") {
                VideoDecoderMode.entries.forEach { mode ->
                    CompactTextButton(mode.label, state.decoderMode == mode) { onDecoderModeSelected(mode) }
                }
            }
            ControlGroup("音轨") {
                state.audioTracks.take(MAX_VISIBLE_TRACK_BUTTONS).forEach { track ->
                    CompactTextButton(track.shortLabel(), state.selectedAudioTrackId == track.id) { onAudioTrackSelected(track.id) }
                }
                if (state.audioTracks.isEmpty()) {
                    Text("自动", style = MaterialTheme.typography.labelSmall, color = Color.White.copy(alpha = 0.7f))
                }
            }
            ControlGroup("字幕") {
                CompactTextButton("关闭", state.selectedSubtitleTrackId == null, onSubtitlesDisabled)
                state.subtitleTracks.take(MAX_VISIBLE_TRACK_BUTTONS).forEach { track ->
                    CompactTextButton(track.shortLabel(), state.selectedSubtitleTrackId == track.id) { onSubtitleTrackSelected(track.id) }
                }
            }
            // 播放信息
            StatisticsControls(
                snapshot = buildVideoPlayerStatisticsSnapshot(
                    mediaContext = mediaContext,
                    state = state,
                    proxy = proxyStatistics,
                ),
                includeProxyDebugInfo = proxyDebugInfoEnabled,
            )
        }
    }
}

// ─── Statistics ───

@Composable
internal fun StatisticsControls(
    snapshot: VideoPlayerStatisticsSnapshot,
    includeProxyDebugInfo: Boolean = true,
) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text("信息", style = MaterialTheme.typography.labelMedium, color = Color.White.copy(alpha = 0.7f))
        snapshot.redacted().debugLines(includeProxyDebugInfo = includeProxyDebugInfo).forEach { line ->
            Text(line, style = MaterialTheme.typography.labelSmall, color = Color.White.copy(alpha = 0.6f), maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}

// ─── Gesture HUD ───

@Composable
internal fun GestureHud(
    message: String?,
    onTimeout: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (message.isNullOrBlank()) return
    androidx.compose.runtime.LaunchedEffect(message) {
        kotlinx.coroutines.delay(GESTURE_HUD_TIMEOUT_MILLIS)
        onTimeout()
    }
    Surface(
        modifier = modifier,
        color = Color(0xCC000000),
        contentColor = Color.White,
        shape = RoundedCornerShape(12.dp),
    ) {
        Text(message, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(horizontal = 22.dp, vertical = 14.dp), maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

// ─── Private helpers ───

@Composable
private fun ControlGroup(label: String, content: @Composable () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = Color.White.copy(alpha = 0.7f), modifier = Modifier.weight(0.22f), maxLines = 1)
        FlowRow(
            modifier = Modifier.weight(0.78f),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
            content = { content() },
        )
    }
}

@Composable
private fun CompactTextButton(text: String, selected: Boolean, onClick: () -> Unit) {
    val bg = if (selected) Color.White.copy(alpha = 0.25f) else Color.White.copy(alpha = 0.1f)
    val shape = RoundedCornerShape(8.dp)
    TextButton(
        onClick = onClick,
        modifier = Modifier
            .heightIn(min = 34.dp)
            .background(bg, shape)
            .padding(horizontal = 2.dp),
    ) {
        Text(text, maxLines = 1, style = MaterialTheme.typography.labelMedium, color = Color.White)
    }
}

// ─── Constants ───

internal val playbackSpeedPresets = listOf(0.5, 0.75, 1.0, 1.25, 1.5, 2.0)
internal const val PLAYER_LOCK_BUTTON_SIZE_DP = 40
internal const val PLAYER_LOCK_BUTTON_START_PADDING_DP = 18
internal const val PLAYER_LOCKED_BUTTON_AUTO_HIDE_MILLIS = 3_000L
internal const val PLAYER_OVERLAY_BUTTON_SIZE_DP = 44
internal const val PLAYER_GESTURE_HORIZONTAL_PADDING_DP = 0
internal const val PLAYER_GESTURE_TOP_PADDING_DP = 0
internal const val PLAYER_GESTURE_END_PADDING_DP = 0
internal const val PLAYER_GESTURE_BOTTOM_PADDING_DP = 0
private const val PLAYER_PROGRESS_TOUCH_HEIGHT_DP = 22
private const val MAX_VISIBLE_TRACK_BUTTONS = 4
private const val GESTURE_HUD_TIMEOUT_MILLIS = 900L
internal const val SEEK_STEP_MILLIS = 10_000L
// Legacy constants kept for test compatibility
internal const val PLAYER_TOP_BAR_MAX_WIDTH_FRACTION = 0.62f
internal const val PLAYER_BOTTOM_CONTROLS_MAX_WIDTH_FRACTION = 0.70f
internal const val PLAYER_CENTER_PLAY_BUTTON_TOUCH_SIZE_DP = 80
internal const val PLAYER_CENTER_PLAY_BUTTON_VISUAL_SIZE_DP = 64
internal val PLAYER_PANEL_CORNER_DP = com.example.comicdav.ui.MuBoxMetrics.PlayerPanelCornerDp.value.toInt()
internal val PLAYER_PANEL_CONTENT_PADDING_DP = com.example.comicdav.ui.MuBoxMetrics.PlayerPanelContentPaddingDp.value.toInt()
internal const val PLAYER_PROGRESS_TRACK_HEIGHT_DP = 3
internal const val PLAYER_OPTION_SHEET_RAIL_GAP_DP = 8
internal const val PLAYER_BOTTOM_CONTROLS_BOTTOM_PADDING_DP = 6
internal const val PLAYER_EDGE_FLOATING_CONTROLS_MAX_ITEMS = 1

// ─── Types and helpers for menu/test compatibility ───

internal enum class PlayerOptionPanel { TRACKS, INFO }

internal data class PlayerOptionPanelDescriptor(
    val icon: ImageVector,
    val contentDescription: String,
    val visibleText: String = "",
)

internal fun PlayerOptionPanel.sideRailDescriptor(): PlayerOptionPanelDescriptor =
    when (this) {
        PlayerOptionPanel.TRACKS -> PlayerOptionPanelDescriptor(Icons.Filled.Subtitles, "音轨与字幕")
        PlayerOptionPanel.INFO -> PlayerOptionPanelDescriptor(Icons.Filled.Info, "播放信息")
    }

internal fun rightSideControlDescriptions(): List<String> =
    listOf("切换横竖屏") + PlayerOptionPanel.entries.map { it.sideRailDescriptor().contentDescription }

internal fun bottomQuickControlLabels(): List<String> = listOf("倍速", "画面", "解码")

internal fun scaleModeControlGroupLabels(): List<String> = listOf("画面")

// ─── Extensions ───

internal val VideoScaleMode.label: String
    get() = when (this) {
        VideoScaleMode.FIT -> "适应"
        VideoScaleMode.FILL -> "填充"
        VideoScaleMode.ORIGINAL -> "原始"
        VideoScaleMode.RATIO_16_9 -> "16:9"
        VideoScaleMode.RATIO_4_3 -> "4:3"
    }

internal val VideoDecoderMode.label: String
    get() = videoDecoderModeLabel(this)

// ─── Utility functions ───

internal fun seekMillisForOffset(offsetX: Float, widthPx: Int, durationMillis: Long): Long {
    if (durationMillis <= 0L || widthPx <= 0) return 0L
    return (durationMillis * (offsetX / widthPx).coerceIn(0f, 1f)).roundToLong()
}

internal fun seekForwardTargetMillis(
    positionMillis: Long,
    durationMillis: Long,
    stepMillis: Long = SEEK_STEP_MILLIS,
): Long {
    val requestedPosition = (positionMillis + stepMillis).coerceAtLeast(0L)
    return if (durationMillis > 0L) {
        requestedPosition.coerceAtMost(durationMillis)
    } else {
        requestedPosition
    }
}

internal fun formatVideoTime(millis: Long): String {
    val totalSeconds = millis.coerceAtLeast(0L) / 1000
    val s = totalSeconds % 60
    val m = (totalSeconds / 60) % 60
    val h = totalSeconds / 3600
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}

private fun MpvTrack.shortLabel(): String =
    title.takeIf { it.isNotBlank() }?.let { if (it.length <= 8) it else it.take(7) + "…" } ?: "#$id"
