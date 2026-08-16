package org.mubox.reader.video.player

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ViewList
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import org.mubox.reader.ui.MuBoxHeaderBar
import org.mubox.reader.ui.MuBoxMetrics
import org.mubox.reader.ui.rememberMuBoxColors
import java.io.File
import kotlin.math.roundToLong

/**
 * 「听视频」独立竖屏界面。配色、形状与控件状态全部来自应用统一主题，
 * 播放内核仍是同一个 [MpvController]，本界面只负责竖屏下的展示与操作。
 */
@Composable
internal fun AudioListenScreen(
    state: MpvPlayerState,
    progress: VideoPlaybackProgressState,
    mediaContext: VideoPlayerMediaContext,
    episodeQueue: VideoEpisodeQueue?,
    currentEpisodeIndex: Int,
    isEpisodeSwitching: Boolean,
    sleepTimerState: SleepTimerState,
    onExitListenMode: () -> Unit,
    onPlayPause: () -> Unit,
    onSeek: (Long) -> Unit,
    onSpeedSelected: (Double) -> Unit,
    onPreviousEpisode: () -> Unit,
    onNextEpisode: () -> Unit,
    onEpisodeSelected: (Int) -> Unit,
    onSleepTimerSelected: (SleepTimerMode) -> Unit,
    onConfigureSystemBars: () -> Unit,
    onRestoreSystemBars: () -> Unit,
) {
    DisposableEffect(Unit) {
        onConfigureSystemBars()
        onDispose {
            onRestoreSystemBars()
        }
    }

    var episodePageVisible by remember { mutableStateOf(false) }
    var playbackSettingsVisible by remember { mutableStateOf(false) }

    BackHandler(enabled = episodePageVisible || playbackSettingsVisible) {
        episodePageVisible = false
        playbackSettingsVisible = false
    }

    val colors = rememberMuBoxColors()
    val episodeCount = episodeQueue?.episodes?.size
    val hasPreviousEpisode = episodeQueue?.let { currentEpisodeIndex > 0 } == true
    val hasNextEpisode = episodeQueue?.let { currentEpisodeIndex < it.episodes.lastIndex } == true
    Surface(modifier = Modifier.fillMaxSize(), color = colors.background) {
        Box(modifier = Modifier.fillMaxSize()) {
            BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
                val artworkSize = minOf(maxWidth * 0.72f, maxHeight * 0.30f)
                    .coerceIn(LISTEN_COVER_MIN_SIZE_DP.dp, LISTEN_COVER_MAX_SIZE_DP.dp)
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .navigationBarsPadding(),
                ) {
                    ListenTopBar(
                        onExitListenMode = onExitListenMode,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                            .padding(horizontal = MuBoxMetrics.PageHorizontalPaddingDp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Spacer(modifier = Modifier.weight(0.55f).heightIn(min = 12.dp))
                        ListenMediaArtwork(
                            artworkPath = mediaContext.artworkPath,
                            centerLabel = listenDiscCenterLabel(currentEpisodeIndex, episodeCount),
                            size = artworkSize,
                            modifier = Modifier.align(Alignment.CenterHorizontally),
                        )
                        Text(
                            text = state.displayName,
                            style = MaterialTheme.typography.titleMedium,
                            color = colors.text,
                            textAlign = TextAlign.Center,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 20.dp),
                        )
                        Text(
                            text = listenSubtitleText(episodeCount, currentEpisodeIndex, mediaContext.source),
                            style = MaterialTheme.typography.labelMedium,
                            color = colors.textTertiary,
                            textAlign = TextAlign.Center,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 4.dp),
                        )
                        Spacer(modifier = Modifier.weight(1f).heightIn(min = 18.dp))
                        ListenSeekSection(progress = progress, onSeek = onSeek)
                        ListenBottomControlBar(
                            isPaused = state.isPaused,
                            hasPreviousEpisode = hasPreviousEpisode,
                            hasNextEpisode = hasNextEpisode,
                            isEpisodeSwitching = isEpisodeSwitching,
                            hasEpisodes = episodeCount != null && episodeCount > 0,
                            hasCustomPlaybackSettings = state.playbackSpeed != 1.0 || sleepTimerState.isActive,
                            onPreviousEpisode = onPreviousEpisode,
                            onNextEpisode = onNextEpisode,
                            onPlayPause = onPlayPause,
                            onPlaybackSettingsClick = { playbackSettingsVisible = true },
                            onEpisodesClick = { episodePageVisible = true },
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 14.dp, bottom = 12.dp),
                        )
                    }
                }
            }

            if (episodePageVisible && episodeQueue != null) {
                ListenEpisodeSheet(
                    queue = episodeQueue,
                    currentEpisodeIndex = currentEpisodeIndex,
                    isSwitching = isEpisodeSwitching,
                    onDismiss = { episodePageVisible = false },
                    onEpisodeSelected = { index ->
                        episodePageVisible = false
                        onEpisodeSelected(index)
                    },
                    modifier = Modifier.fillMaxSize(),
                )
            }

            if (playbackSettingsVisible) {
                ListenPlaybackSettingsSheet(
                    playbackSpeed = state.playbackSpeed,
                    sleepTimerState = sleepTimerState,
                    onSpeedSelected = onSpeedSelected,
                    onSleepTimerSelected = onSleepTimerSelected,
                    onDismiss = { playbackSettingsVisible = false },
                )
            }
        }
    }
}

// ─── Top bar: 左上返回视频画面 ───

@Composable
private fun ListenTopBar(
    onExitListenMode: () -> Unit,
    modifier: Modifier = Modifier,
) {
    MuBoxHeaderBar(
        title = "听视频",
        modifier = modifier,
        navigationIcon = {
            IconButton(
                onClick = onExitListenMode,
                modifier = Modifier.size(PLAYER_OVERLAY_BUTTON_SIZE_DP.dp),
            ) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "退出听视频，返回视频画面",
                    modifier = Modifier.size(24.dp),
                )
            }
        },
    )
}

// ─── 媒体占位卡：沿用统一主题的面板、形状与强调色 ───

@Composable
private fun ListenMediaArtwork(
    artworkPath: String?,
    centerLabel: String,
    size: Dp,
    modifier: Modifier = Modifier,
) {
    val colors = rememberMuBoxColors()
    Surface(
        modifier = modifier
            .size(size)
            .semantics { contentDescription = LISTEN_COVER_CONTENT_DESCRIPTION },
        shape = MaterialTheme.shapes.extraLarge,
        color = colors.panel,
        contentColor = colors.mediaAccent,
        border = BorderStroke(1.dp, colors.border),
    ) {
        if (artworkPath != null) {
            AsyncImage(
                model = File(artworkPath),
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
        } else {
            Column(
                modifier = Modifier.fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Icon(
                    imageVector = Icons.Filled.Headphones,
                    contentDescription = null,
                    modifier = Modifier.size(size * 0.34f),
                )
                Text(
                    text = centerLabel,
                    style = MaterialTheme.typography.titleLarge,
                    color = colors.text,
                    maxLines = 1,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        }
    }
}

// ─── 进度区 ───

@Composable
private fun ListenSeekSection(
    progress: VideoPlaybackProgressState,
    onSeek: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    val durationMillis = progress.durationMillis.coerceAtLeast(0L)
    val progressFraction = if (durationMillis > 0L) {
        (progress.positionMillis.toFloat() / durationMillis).coerceIn(0f, 1f)
    } else {
        0f
    }
    Column(modifier = modifier) {
        Slider(
            value = progressFraction,
            onValueChange = { fraction ->
                if (durationMillis > 0L) {
                    onSeek((durationMillis * fraction).roundToLong())
                }
            },
            enabled = durationMillis > 0L,
            modifier = Modifier
                .fillMaxWidth()
                .height(36.dp)
                .semantics { contentDescription = LISTEN_PROGRESS_CONTENT_DESCRIPTION },
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                formatVideoTime(progress.positionMillis),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onBackground,
            )
            Text(
                formatVideoTime(progress.durationMillis),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

// ─── 底部单行控制：设置 / 上一集 / 播放暂停 / 下一集 / 选集 ───

@Composable
private fun ListenBottomControlBar(
    isPaused: Boolean,
    hasPreviousEpisode: Boolean,
    hasNextEpisode: Boolean,
    isEpisodeSwitching: Boolean,
    hasEpisodes: Boolean,
    hasCustomPlaybackSettings: Boolean,
    onPreviousEpisode: () -> Unit,
    onNextEpisode: () -> Unit,
    onPlayPause: () -> Unit,
    onPlaybackSettingsClick: () -> Unit,
    onEpisodesClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = rememberMuBoxColors()
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ListenMenuIconButton(
            icon = Icons.Filled.Settings,
            contentDescription = LISTEN_PLAYBACK_SETTINGS_CONTENT_DESCRIPTION,
            selected = hasCustomPlaybackSettings,
            onClick = onPlaybackSettingsClick,
        )
        ListenTransportIconButton(
            icon = Icons.Filled.SkipPrevious,
            contentDescription = "上一集",
            enabled = hasPreviousEpisode && !isEpisodeSwitching,
            buttonSizeDp = LISTEN_EPISODE_BUTTON_SIZE_DP,
            iconSizeDp = 30,
            onClick = onPreviousEpisode,
        )
        FilledIconButton(
            onClick = onPlayPause,
            enabled = !isEpisodeSwitching,
            modifier = Modifier.size(LISTEN_PLAY_BUTTON_SIZE_DP.dp),
            colors = IconButtonDefaults.filledIconButtonColors(
                containerColor = colors.mediaAccent,
                contentColor = colors.onMediaAccent,
                disabledContainerColor = colors.panelHigh,
                disabledContentColor = colors.textDisabled,
            ),
        ) {
            if (isEpisodeSwitching) {
                CircularProgressIndicator(
                    modifier = Modifier.size(30.dp),
                    strokeWidth = 3.dp,
                    color = colors.textDisabled,
                )
            } else {
                Icon(
                    imageVector = if (isPaused) Icons.Filled.PlayArrow else Icons.Filled.Pause,
                    contentDescription = if (isPaused) "播放" else "暂停",
                    modifier = Modifier.size(36.dp),
                )
            }
        }
        ListenTransportIconButton(
            icon = Icons.Filled.SkipNext,
            contentDescription = "下一集",
            enabled = hasNextEpisode && !isEpisodeSwitching,
            buttonSizeDp = LISTEN_EPISODE_BUTTON_SIZE_DP,
            iconSizeDp = 30,
            onClick = onNextEpisode,
        )
        ListenMenuIconButton(
            icon = Icons.AutoMirrored.Filled.ViewList,
            contentDescription = LISTEN_EPISODE_MENU_CONTENT_DESCRIPTION,
            enabled = hasEpisodes,
            onClick = onEpisodesClick,
        )
    }
}

@Composable
private fun ListenTransportIconButton(
    icon: ImageVector,
    contentDescription: String,
    enabled: Boolean,
    buttonSizeDp: Int,
    iconSizeDp: Int,
    onClick: () -> Unit,
) {
    val colors = rememberMuBoxColors()
    IconButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.size(buttonSizeDp.dp),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = if (enabled) colors.text else colors.textDisabled,
            modifier = Modifier.size(iconSizeDp.dp),
        )
    }
}

@Composable
private fun ListenMenuIconButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
    selected: Boolean = false,
) {
    val colors = rememberMuBoxColors()
    IconButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.size(LISTEN_MENU_BUTTON_SIZE_DP.dp),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = when {
                !enabled -> colors.textDisabled
                selected -> colors.mediaAccent
                else -> colors.text
            },
            modifier = Modifier.size(LISTEN_MENU_ICON_SIZE_DP.dp),
        )
    }
}

// ─── 左侧播放设置菜单：倍速 + 定时关闭 ───

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ListenPlaybackSettingsSheet(
    playbackSpeed: Double,
    sleepTimerState: SleepTimerState,
    onSpeedSelected: (Double) -> Unit,
    onSleepTimerSelected: (SleepTimerMode) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 24.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = "播放设置",
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = "倍速",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                playbackSpeedPresets.forEach { speed ->
                    ListenOptionChip(
                        text = "${speed}x",
                        selected = playbackSpeed == speed,
                    ) {
                        onSpeedSelected(speed)
                    }
                }
            }
            HorizontalDivider()
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .semantics { contentDescription = LISTEN_TIMER_CONTENT_DESCRIPTION },
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = Icons.Filled.Schedule,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                )
                Text(
                    text = "定时关闭",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                if (sleepTimerState.isActive) {
                    Text(
                        text = sleepTimerState.statusText(),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                SleepTimerMode.entries.forEach { mode ->
                    val label = mode.controlLabel()
                    val buttonText = if (sleepTimerState.mode == mode && mode.durationMillis != null) {
                        "$label ${formatVideoTime(sleepTimerState.remainingMillis)}"
                    } else {
                        label
                    }
                    ListenOptionChip(
                        text = buttonText,
                        selected = sleepTimerState.mode == mode,
                    ) {
                        onSleepTimerSelected(mode)
                    }
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
        }
    }
}

@Composable
private fun ListenOptionChip(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(text = text, maxLines = 1) },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ListenEpisodeSheet(
    queue: VideoEpisodeQueue,
    currentEpisodeIndex: Int,
    isSwitching: Boolean,
    onDismiss: () -> Unit,
    onEpisodeSelected: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = rememberMuBoxColors()
    val parentDirectoryName = queue.parentDirectoryName(currentEpisodeIndex)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        modifier = modifier,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(bottom = 8.dp),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "选集",
                        style = MaterialTheme.typography.titleLarge,
                        color = colors.text,
                    )
                    if (parentDirectoryName != null) {
                        Text(
                            text = parentDirectoryName,
                            style = MaterialTheme.typography.bodyMedium,
                            color = colors.muted,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Text(
                        text = "第 ${(currentEpisodeIndex + 1).coerceAtMost(queue.episodes.size)} / ${queue.episodes.size} 集",
                        style = MaterialTheme.typography.bodyMedium,
                        color = colors.muted,
                    )
                }
                if (isSwitching) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(24.dp),
                        strokeWidth = 2.dp,
                    )
                }
            }
            HorizontalDivider(color = colors.separator)
            LazyColumn(modifier = Modifier.heightIn(max = 520.dp)) {
                itemsIndexed(
                    items = queue.episodes,
                    key = { _, episode -> episode.playbackKey },
                ) { index, episode ->
                    val isCurrent = index == currentEpisodeIndex
                    ListItem(
                        headlineContent = {
                            Text(
                                text = episode.displayName,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        },
                        leadingContent = {
                            Text(
                                text = (index + 1).toString().padStart(2, '0'),
                                style = MaterialTheme.typography.labelLarge,
                            )
                        },
                        trailingContent = {
                            if (isCurrent) {
                                Icon(
                                    imageVector = Icons.Filled.Check,
                                    contentDescription = "正在播放",
                                )
                            }
                        },
                        colors = ListItemDefaults.colors(
                            containerColor = if (isCurrent) {
                                MaterialTheme.colorScheme.secondaryContainer
                            } else {
                                MaterialTheme.colorScheme.surface
                            },
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(
                                enabled = !isCurrent && !isSwitching,
                                onClick = { onEpisodeSelected(index) },
                            )
                            .semantics { selected = isCurrent },
                    )
                }
            }
        }
    }
}

// ─── 可测试的纯展示辅助 ───

internal fun listenScreenTransportControlDescriptions(): List<String> =
    listOf("退出听视频，返回视频画面", "播放", "暂停", "上一集", "下一集")

internal fun listenScreenQuickControlLabels(): List<String> =
    listOf("播放设置", "倍速", "定时关闭", "选集")

/** 媒体占位卡标签：多集时显示当前集数，否则显示音符。 */
internal fun listenDiscCenterLabel(episodeIndex: Int, queueSize: Int?): String =
    if (queueSize != null && queueSize > 1) (episodeIndex + 1).toString() else "♪"

/** 标题下的副标题：有剧集队列时显示“第 X / N 集”，否则显示来源。 */
internal fun listenSubtitleText(queueSize: Int?, currentEpisodeIndex: Int, source: String): String {
    if (queueSize != null && queueSize > 0) {
        val position = (currentEpisodeIndex + 1).coerceIn(1, queueSize)
        return "第 $position / $queueSize 集 · 听视频"
    }
    return "听视频 · ${friendlyVideoSourceLabel(source)}"
}

internal fun friendlyVideoSourceLabel(source: String): String =
    when (source) {
        VideoPlayerLaunchContract.SOURCE_LOCAL -> "本地视频"
        VideoPlayerLaunchContract.SOURCE_WEB_DAV -> "WebDAV"
        else -> source
    }

internal fun listenSelectedAudioTrackLabel(state: MpvPlayerState): String =
    state.audioTracks.firstOrNull { it.id == state.selectedAudioTrackId }?.shortLabel() ?: "自动"

// ─── Constants ───

internal const val LISTEN_PLAY_BUTTON_SIZE_DP = 64
internal const val LISTEN_EPISODE_BUTTON_SIZE_DP = 48
internal const val LISTEN_MENU_BUTTON_SIZE_DP = 44
internal const val LISTEN_MENU_ICON_SIZE_DP = 24
internal const val LISTEN_COVER_MIN_SIZE_DP = 156
internal const val LISTEN_COVER_MAX_SIZE_DP = 280
internal const val LISTEN_COVER_CONTENT_DESCRIPTION = "当前视频封面"
internal const val LISTEN_PROGRESS_CONTENT_DESCRIPTION = "播放进度"
internal const val LISTEN_TIMER_CONTENT_DESCRIPTION = "定时关闭"
internal const val LISTEN_PLAYBACK_SETTINGS_CONTENT_DESCRIPTION = "播放设置：倍速和定时关闭"
internal const val LISTEN_EPISODE_MENU_CONTENT_DESCRIPTION = "选集"
