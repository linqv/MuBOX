package com.example.comicdav.video.player

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.ScreenRotation
import androidx.compose.material.icons.filled.Subtitles
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlin.math.roundToLong

@Composable
internal fun PlayerTopBar(
    title: String,
    source: String,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .background(
                Brush.verticalGradient(
                    colors = listOf(
                        Color(0xCC000000),
                        Color(0x66000000),
                        Color.Transparent,
                    ),
                ),
            ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 14.dp, end = 14.dp, top = 12.dp, bottom = 22.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PlayerOverlayIconButton(
                icon = Icons.Filled.Close,
                contentDescription = "关闭",
                onClick = onClose,
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    color = Color.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = source.videoSourceLabel(),
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White.copy(alpha = 0.7f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
internal fun PlayerLockButton(
    controlsLocked: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    PlayerOverlayIconButton(
        icon = if (controlsLocked) Icons.Filled.Lock else Icons.Filled.LockOpen,
        contentDescription = if (controlsLocked) "解锁控制" else "锁定控制",
        onClick = onClick,
        modifier = modifier,
        selected = controlsLocked,
        sizeDp = PLAYER_LOCK_BUTTON_SIZE_DP,
    )
}

@Composable
internal fun PlayerCenterPlayPauseButton(
    isPaused: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier.size(PLAYER_CENTER_PLAY_BUTTON_TOUCH_SIZE_DP.dp),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(PLAYER_CENTER_PLAY_BUTTON_VISUAL_SIZE_DP.dp)
                .background(PlayerCenterPlayButtonColor, CircleShape)
                .border(1.dp, Color.White.copy(alpha = 0.15f), CircleShape)
                .clickable(role = Role.Button, onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = if (isPaused) Icons.Filled.PlayArrow else Icons.Filled.Pause,
                contentDescription = if (isPaused) "播放" else "暂停",
                tint = Color.White,
                modifier = Modifier.size(36.dp),
            )
        }
    }
}

@Composable
internal fun PlayerOverlayIconButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    sizeDp: Int = PLAYER_OVERLAY_BUTTON_SIZE_DP,
) {
    val backgroundColor = if (selected) PlayerAccentColor else PlayerOverlayColor
    val contentColor = if (selected) PlayerOnAccentColor else Color.White
    val buttonShape = CircleShape
    val borderColor = if (selected) {
        Color.White.copy(alpha = 0.46f)
    } else {
        Color.White.copy(alpha = 0.15f)
    }
    IconButton(
        onClick = onClick,
        modifier = modifier
            .size(sizeDp.dp)
            .background(backgroundColor, buttonShape)
            .border(1.dp, borderColor, buttonShape),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = contentColor,
            modifier = Modifier.size(20.dp),
        )
    }
}

@Composable
internal fun PlayerBottomControls(
    state: MpvPlayerState,
    activeControl: PlayerBottomQuickControl?,
    onActiveControlChanged: (PlayerBottomQuickControl) -> Unit,
    onSeek: (Long) -> Unit,
    onSpeedSelected: (Double) -> Unit,
    onScaleModeSelected: (VideoScaleMode) -> Unit,
    onDecoderModeSelected: (VideoDecoderMode) -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .background(
                Brush.verticalGradient(
                    colors = listOf(
                        Color.Transparent,
                        Color(0x99000000),
                        Color(0xCC000000),
                    ),
                ),
            )
            .padding(
                start = 18.dp,
                top = 56.dp,
                end = 18.dp,
                bottom = (PLAYER_BOTTOM_CONTROLS_BOTTOM_PADDING_DP + 6).dp,
            ),
        contentAlignment = Alignment.BottomCenter,
    ) {
        com.example.comicdav.ui.MuBoxPlayerPanel(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                activeControl?.let { control ->
                    PlayerBottomQuickSelectionPanel(
                        control = control,
                        state = state,
                        onSpeedSelected = onSpeedSelected,
                        onScaleModeSelected = onScaleModeSelected,
                        onDecoderModeSelected = onDecoderModeSelected,
                    )
                }
                if (state.errorMessage != null) {
                    Surface(
                        color = Color(0xCC2A0D14),
                        contentColor = MaterialTheme.colorScheme.error,
                        shape = RoundedCornerShape(10.dp),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.28f)),
                    ) {
                        Text(
                            text = state.errorMessage,
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                        )
                    }
                }
                PlayerBottomQuickControls(
                    state = state,
                    activeControl = activeControl,
                    onActiveControlChanged = onActiveControlChanged,
                )
                PlayerProgressControls(
                    state = state,
                    onSeek = onSeek,
                )
            }
        }
    }
}

@Composable
internal fun PlayerBottomQuickControls(
    state: MpvPlayerState,
    activeControl: PlayerBottomQuickControl?,
    onActiveControlChanged: (PlayerBottomQuickControl) -> Unit,
) {
    FlowRow(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = PLAYER_BOTTOM_QUICK_CONTROL_HEIGHT_DP.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
        maxItemsInEachRow = 3,
    ) {
        BottomQuickButton(
            text = state.playbackSpeed.trimmedSpeed() + "x",
            contentDescription = "倍速",
            selected = activeControl == PlayerBottomQuickControl.SPEED,
            onClick = { onActiveControlChanged(PlayerBottomQuickControl.SPEED) },
        )
        BottomQuickButton(
            text = state.scaleMode.label,
            contentDescription = "画面",
            selected = activeControl == PlayerBottomQuickControl.SCALE,
            onClick = { onActiveControlChanged(PlayerBottomQuickControl.SCALE) },
        )
        BottomQuickButton(
            text = state.decoderMode.label,
            contentDescription = "解码",
            selected = activeControl == PlayerBottomQuickControl.DECODER,
            onClick = { onActiveControlChanged(PlayerBottomQuickControl.DECODER) },
        )
    }
}

@Composable
internal fun PlayerProgressControls(
    state: MpvPlayerState,
    onSeek: (Long) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        ThinSeekBar(
            positionMillis = state.positionMillis,
            durationMillis = state.durationMillis,
            onSeek = onSeek,
            modifier = Modifier.fillMaxWidth(),
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = formatVideoTime(state.positionMillis),
                style = MaterialTheme.typography.labelMedium,
                color = Color.White,
                maxLines = 1,
            )
            Text(
                text = formatVideoTime(state.durationMillis),
                style = MaterialTheme.typography.labelMedium,
                color = Color.White.copy(alpha = 0.7f),
                maxLines = 1,
            )
        }
    }
}

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
            .heightIn(
                min = PLAYER_PROGRESS_TOUCH_HEIGHT_DP.dp,
                max = PLAYER_PROGRESS_TOUCH_HEIGHT_DP.dp,
            )
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
        val strokeWidth = PLAYER_PROGRESS_TRACK_HEIGHT_DP.dp.toPx()
        val progressX = size.width * progress
        drawLine(
            color = PlayerProgressTrackColor,
            start = Offset(0f, trackY),
            end = Offset(size.width, trackY),
            strokeWidth = strokeWidth,
            cap = StrokeCap.Round,
        )
        drawLine(
            color = PlayerProgressColor,
            start = Offset(0f, trackY),
            end = Offset(progressX, trackY),
            strokeWidth = strokeWidth,
            cap = StrokeCap.Round,
        )
        drawCircle(
            color = PlayerProgressColor,
            radius = PLAYER_PROGRESS_THUMB_RADIUS_DP.dp.toPx(),
            center = Offset(progressX, trackY),
        )
    }
}

@Composable
internal fun PlayerSideControls(
    state: MpvPlayerState,
    activePanel: PlayerOptionPanel?,
    onPanelSelected: (PlayerOptionPanel) -> Unit,
    onDismiss: () -> Unit,
    onAudioTrackSelected: (Int) -> Unit,
    onSubtitleTrackSelected: (Int) -> Unit,
    onSubtitlesDisabled: () -> Unit,
    onOrientationToggle: () -> Unit,
    mediaContext: VideoPlayerMediaContext,
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(modifier = modifier) {
        val isLandscape = maxWidth > maxHeight
        val railAndMargins = (PLAYER_OVERLAY_BUTTON_SIZE_DP + PLAYER_OPTION_SHEET_RAIL_GAP_DP + 24).dp
        val availableSheetMaxWidth = (maxWidth - railAndMargins).coerceAtLeast(220.dp)
        val sheetMaxWidth = availableSheetMaxWidth.coerceAtMost(if (isLandscape) 320.dp else 360.dp)

        Row(
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .padding(end = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(PLAYER_OPTION_SHEET_RAIL_GAP_DP.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (activePanel != null) {
                PlayerOptionSheet(
                    panel = activePanel,
                    state = state,
                    mediaContext = mediaContext,
                    onDismiss = onDismiss,
                    onAudioTrackSelected = onAudioTrackSelected,
                    onSubtitleTrackSelected = onSubtitleTrackSelected,
                    onSubtitlesDisabled = onSubtitlesDisabled,
                    modifier = Modifier.widthIn(min = 220.dp, max = sheetMaxWidth),
                )
            }
            EdgeFloatingControls(
                state = state,
                activePanel = activePanel,
                compact = isLandscape,
                onOrientationToggle = onOrientationToggle,
                onPanelSelected = onPanelSelected,
            )
        }
    }
}

@Composable
private fun EdgeFloatingControls(
    state: MpvPlayerState,
    activePanel: PlayerOptionPanel?,
    compact: Boolean,
    onOrientationToggle: () -> Unit,
    onPanelSelected: (PlayerOptionPanel) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (compact) {
        FlowRow(
            modifier = modifier.widthIn(max = PLAYER_OVERLAY_BUTTON_SIZE_DP.dp),
            horizontalArrangement = Arrangement.Start,
            verticalArrangement = Arrangement.spacedBy(6.dp),
            maxItemsInEachRow = PLAYER_EDGE_FLOATING_CONTROLS_MAX_ITEMS,
        ) {
            PlayerOverlayIconButton(
                icon = Icons.Filled.ScreenRotation,
                contentDescription = PLAYER_ORIENTATION_TOGGLE_CONTENT_DESCRIPTION,
                onClick = onOrientationToggle,
            )
            PlayerOptionPanel.entries.forEach { panel ->
                FloatingPanelButton(
                    panel = panel,
                    state = state,
                    selected = activePanel == panel,
                    onClick = { onPanelSelected(panel) },
                )
            }
        }
        return
    }

    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(8.dp),
        horizontalAlignment = Alignment.End,
    ) {
        PlayerOverlayIconButton(
            icon = Icons.Filled.ScreenRotation,
            contentDescription = PLAYER_ORIENTATION_TOGGLE_CONTENT_DESCRIPTION,
            onClick = onOrientationToggle,
        )
        PlayerOptionPanel.entries.forEach { panel ->
            FloatingPanelButton(
                panel = panel,
                state = state,
                selected = activePanel == panel,
                onClick = { onPanelSelected(panel) },
            )
        }
    }
}

@Composable
private fun FloatingPanelButton(
    panel: PlayerOptionPanel,
    state: MpvPlayerState,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val descriptor = panel.sideRailDescriptor()
    Box {
        PlayerOverlayIconButton(
            icon = descriptor.icon,
            contentDescription = descriptor.contentDescription,
            onClick = onClick,
            selected = selected,
        )
        panel.statusBadgeText(state)?.let { badge ->
            Surface(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .size(width = 24.dp, height = 18.dp),
                color = if (selected) PlayerOnAccentColor else PlayerAccentColor,
                contentColor = if (selected) PlayerAccentColor else PlayerOnAccentColor,
                shape = MaterialTheme.shapes.small,
                border = BorderStroke(1.dp, Color.White.copy(alpha = 0.32f)),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text(
                        text = badge,
                        style = MaterialTheme.typography.labelSmall,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

@Composable
internal fun PlayerOptionSheet(
    panel: PlayerOptionPanel?,
    state: MpvPlayerState,
    mediaContext: VideoPlayerMediaContext,
    onDismiss: () -> Unit,
    onAudioTrackSelected: (Int) -> Unit,
    onSubtitleTrackSelected: (Int) -> Unit,
    onSubtitlesDisabled: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (panel == null) return

    com.example.comicdav.ui.MuBoxPlayerPanel(
        modifier = modifier.widthIn(min = 220.dp, max = 360.dp),
        color = PlayerSheetColor,
        borderColor = Color.White.copy(alpha = 0.12f),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = panel.title,
                    style = MaterialTheme.typography.titleMedium,
                    color = Color.White,
                )
                IconButton(
                    onClick = onDismiss,
                    modifier = Modifier
                        .size(36.dp)
                        .background(PlayerChipColor, CircleShape)
                        .border(1.dp, Color.White.copy(alpha = 0.15f), CircleShape),
                ) {
                    Icon(
                        imageVector = Icons.Filled.Close,
                        contentDescription = "收起",
                        tint = Color.White,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
            when (panel) {
                PlayerOptionPanel.TRACKS -> TrackSelectionControls(
                    audioTracks = state.audioTracks,
                    subtitleTracks = state.subtitleTracks,
                    selectedAudioTrackId = state.selectedAudioTrackId,
                    selectedSubtitleTrackId = state.selectedSubtitleTrackId,
                    onAudioTrackSelected = onAudioTrackSelected,
                    onSubtitleTrackSelected = onSubtitleTrackSelected,
                    onSubtitlesDisabled = onSubtitlesDisabled,
                )
                PlayerOptionPanel.INFO -> StatisticsControls(
                    snapshot = buildVideoPlayerStatisticsSnapshot(
                        mediaContext = mediaContext,
                        state = state,
                    ),
                )
            }
        }
    }
}

@Composable
internal fun PlaybackSpeedControls(
    selectedSpeed: Double,
    onSpeedSelected: (Double) -> Unit,
) {
    ControlGroup(label = "倍速") {
        playbackSpeedPresets.forEach { speed ->
            CompactTextButton(
                text = "${speed}x",
                selected = selectedSpeed == speed,
                onClick = { onSpeedSelected(speed) },
            )
        }
    }
}

@Composable
internal fun TrackSelectionControls(
    audioTracks: List<MpvTrack>,
    subtitleTracks: List<MpvTrack>,
    selectedAudioTrackId: Int?,
    selectedSubtitleTrackId: Int?,
    onAudioTrackSelected: (Int) -> Unit,
    onSubtitleTrackSelected: (Int) -> Unit,
    onSubtitlesDisabled: () -> Unit,
) {
    ControlGroup(label = "音轨") {
        audioTracks.take(MAX_VISIBLE_TRACK_BUTTONS).forEach { track ->
            CompactTextButton(
                text = track.shortLabel(),
                selected = selectedAudioTrackId == track.id,
                onClick = { onAudioTrackSelected(track.id) },
            )
        }
        if (audioTracks.isEmpty()) {
            Text(text = "自动", style = MaterialTheme.typography.labelSmall, color = Color.White.copy(alpha = 0.7f))
        }
    }
    ControlGroup(label = "字幕") {
        CompactTextButton(
            text = "关闭",
            selected = selectedSubtitleTrackId == null,
            onClick = onSubtitlesDisabled,
        )
        subtitleTracks.take(MAX_VISIBLE_TRACK_BUTTONS).forEach { track ->
            CompactTextButton(
                text = track.shortLabel(),
                selected = selectedSubtitleTrackId == track.id,
                onClick = { onSubtitleTrackSelected(track.id) },
            )
        }
    }
}

@Composable
internal fun StatisticsControls(
    snapshot: VideoPlayerStatisticsSnapshot,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        snapshot.redacted().debugLines().forEach { line ->
            Text(
                text = line,
                style = MaterialTheme.typography.labelSmall,
                color = Color.White.copy(alpha = 0.8f),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

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
        shape = RoundedCornerShape(24.dp),
        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.15f)),
    ) {
        Text(
            text = message,
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(horizontal = 22.dp, vertical = 14.dp),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

internal enum class PlayerOptionPanel {
    TRACKS,
    INFO,
}

internal enum class PlayerBottomQuickControl {
    SPEED,
    SCALE,
    DECODER,
}

internal data class PlayerOptionPanelDescriptor(
    val icon: ImageVector,
    val contentDescription: String,
    val visibleText: String = "",
)

private const val PLAYER_ORIENTATION_TOGGLE_CONTENT_DESCRIPTION = "切换横竖屏"

internal fun rightSideControlDescriptions(): List<String> =
    listOf(PLAYER_ORIENTATION_TOGGLE_CONTENT_DESCRIPTION) +
        PlayerOptionPanel.entries.map { it.sideRailDescriptor().contentDescription }

internal fun PlayerOptionPanel.sideRailDescriptor(): PlayerOptionPanelDescriptor =
    when (this) {
        PlayerOptionPanel.TRACKS -> PlayerOptionPanelDescriptor(
            icon = Icons.Filled.Subtitles,
            contentDescription = "音轨与字幕",
        )
        PlayerOptionPanel.INFO -> PlayerOptionPanelDescriptor(
            icon = Icons.Filled.Info,
            contentDescription = "播放信息",
        )
    }

internal fun bottomQuickControlLabels(): List<String> = listOf("倍速", "画面", "解码")

internal fun scaleModeControlGroupLabels(): List<String> = listOf("画面")

@Composable
private fun BottomQuickButton(
    text: String,
    contentDescription: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val backgroundColor = if (selected) PlayerChipSelectedColor else PlayerChipColor
    val contentColor = if (selected) PlayerOnAccentColor else Color.White
    val shape = RoundedCornerShape(14.dp)
    TextButton(
        onClick = onClick,
        modifier = Modifier
            .size(width = 52.dp, height = 28.dp)
            .background(backgroundColor, shape)
            .border(1.dp, if (selected) PlayerAccentColor.copy(alpha = 0.42f) else Color.White.copy(alpha = 0.15f), shape)
            .semantics { this.contentDescription = contentDescription },
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 4.dp, vertical = 0.dp),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = contentColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun PlayerBottomQuickSelectionPanel(
    control: PlayerBottomQuickControl,
    state: MpvPlayerState,
    onSpeedSelected: (Double) -> Unit,
    onScaleModeSelected: (VideoScaleMode) -> Unit,
    onDecoderModeSelected: (VideoDecoderMode) -> Unit,
) {
    ControlGroup(label = control.label) {
        when (control) {
            PlayerBottomQuickControl.SPEED -> playbackSpeedPresets.forEach { speed ->
                CompactTextButton(
                    text = "${speed}x",
                    selected = state.playbackSpeed == speed,
                    onClick = { onSpeedSelected(speed) },
                )
            }
            PlayerBottomQuickControl.SCALE -> VideoScaleMode.entries.forEach { mode ->
                CompactTextButton(mode.label, state.scaleMode == mode) { onScaleModeSelected(mode) }
            }
            PlayerBottomQuickControl.DECODER -> VideoDecoderMode.entries.forEach { mode ->
                CompactTextButton(mode.label, state.decoderMode == mode) { onDecoderModeSelected(mode) }
            }
        }
    }
}

@Composable
private fun ControlGroup(
    label: String,
    content: @Composable () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = Color.White.copy(alpha = 0.7f),
            modifier = Modifier.weight(0.24f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        FlowRow(
            modifier = Modifier.weight(0.76f),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
            content = { content() },
        )
    }
}

@Composable
private fun CompactTextButton(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val bg = if (selected) PlayerChipSelectedColor else PlayerChipColor
    val fg = if (selected) PlayerOnAccentColor else Color.White
    val shape = RoundedCornerShape(18.dp)
    TextButton(
        onClick = onClick,
        modifier = Modifier
            .size(width = 78.dp, height = 36.dp)
            .background(bg, shape)
            .border(1.dp, if (selected) PlayerAccentColor.copy(alpha = 0.42f) else Color.White.copy(alpha = 0.15f), shape),
    ) {
        Text(
            text = text,
            maxLines = 1,
            style = MaterialTheme.typography.labelMedium,
            color = fg,
        )
    }
}

internal val playbackSpeedPresets = listOf(0.5, 0.75, 1.0, 1.25, 1.5, 2.0)
internal val PlayerOverlayColor = Color(0x66000000)
internal val PlayerSheetColor = Color(0xCC0F172A)
internal val PlayerAccentColor = Color(0xFFEC4899)
internal val PlayerOnAccentColor = Color(0xFFFFFFFF)
internal val PlayerCenterPlayButtonColor = Color(0x4D000000)
internal val PlayerProgressTrackColor = Color(0x4DFFFFFF)
internal val PlayerProgressColor = Color(0xFFEC4899)
internal val PlayerChipColor = Color(0x33FFFFFF)
internal val PlayerChipSelectedColor = PlayerAccentColor
internal const val PLAYER_CENTER_PLAY_BUTTON_TOUCH_SIZE_DP = 80
internal const val PLAYER_CENTER_PLAY_BUTTON_VISUAL_SIZE_DP = 64
internal val PLAYER_PANEL_CORNER_DP = com.example.comicdav.ui.MuBoxMetrics.PlayerPanelCornerDp.value.toInt()
internal val PLAYER_PANEL_CONTENT_PADDING_DP = com.example.comicdav.ui.MuBoxMetrics.PlayerPanelContentPaddingDp.value.toInt()
internal const val PLAYER_LOCK_BUTTON_SIZE_DP = 40
internal const val PLAYER_LOCK_BUTTON_START_PADDING_DP = 18
internal const val PLAYER_LOCKED_BUTTON_AUTO_HIDE_MILLIS = 3_000L
internal const val PLAYER_OVERLAY_BUTTON_SIZE_DP = 44
internal const val PLAYER_PROGRESS_TRACK_HEIGHT_DP = 3
internal const val PLAYER_OPTION_SHEET_RAIL_GAP_DP = 8
internal const val PLAYER_BOTTOM_CONTROLS_BOTTOM_PADDING_DP = 6
internal const val PLAYER_EDGE_FLOATING_CONTROLS_MAX_ITEMS = 1
internal const val PLAYER_GESTURE_HORIZONTAL_PADDING_DP = 0
internal const val PLAYER_GESTURE_TOP_PADDING_DP = 0
internal const val PLAYER_GESTURE_END_PADDING_DP = 0
internal const val PLAYER_GESTURE_BOTTOM_PADDING_DP = 0
private const val PLAYER_BOTTOM_QUICK_CONTROL_HEIGHT_DP = 30
private const val PLAYER_PROGRESS_TOUCH_HEIGHT_DP = 22
private const val PLAYER_PROGRESS_THUMB_RADIUS_DP = 6
private const val MAX_VISIBLE_TRACK_BUTTONS = 4
private const val GESTURE_HUD_TIMEOUT_MILLIS = 900L

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

internal val PlayerOptionPanel.title: String
    get() = when (this) {
        PlayerOptionPanel.TRACKS -> "音轨 / 字幕"
        PlayerOptionPanel.INFO -> "信息"
    }

internal val PlayerBottomQuickControl.label: String
    get() = when (this) {
        PlayerBottomQuickControl.SPEED -> "倍速"
        PlayerBottomQuickControl.SCALE -> "画面"
        PlayerBottomQuickControl.DECODER -> "解码"
    }

private fun PlayerOptionPanel.statusBadgeText(state: MpvPlayerState): String? =
    when (this) {
        PlayerOptionPanel.TRACKS -> state.subtitleTracks
            .size
            .takeIf { it > 1 }
            ?.toString()
        PlayerOptionPanel.INFO -> null
    }

private fun Double.trimmedSpeed(): String =
    if (this % 1.0 == 0.0) this.toInt().toString() else toString()

private fun String.videoSourceLabel(): String =
    when (lowercase()) {
        VideoPlayerActivity.SOURCE_LOCAL -> "本地视频"
        "webdav" -> "WebDAV"
        else -> this.ifBlank { "视频" }
    }

private fun MpvTrack.shortLabel(): String =
    title.takeIf { it.isNotBlank() }?.let { raw ->
        if (raw.length <= 8) raw else raw.take(7) + "..."
    } ?: "#$id"

internal fun seekMillisForOffset(offsetX: Float, widthPx: Int, durationMillis: Long): Long {
    if (durationMillis <= 0L || widthPx <= 0) return 0L
    val fraction = (offsetX / widthPx).coerceIn(0f, 1f)
    return (durationMillis * fraction).roundToLong()
}

internal fun formatVideoTime(millis: Long): String {
    val totalSeconds = millis.coerceAtLeast(0L) / 1000
    val seconds = totalSeconds % 60
    val minutes = (totalSeconds / 60) % 60
    val hours = totalSeconds / 3600
    return if (hours > 0) {
        "%d:%02d:%02d".format(hours, minutes, seconds)
    } else {
        "%02d:%02d".format(minutes, seconds)
    }
}
