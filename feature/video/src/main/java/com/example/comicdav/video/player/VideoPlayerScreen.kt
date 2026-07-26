package com.example.comicdav.video.player

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.example.comicdav.core.model.settings.Anime4KProfile
import com.example.comicdav.core.model.settings.VideoDecoderMode
import kotlinx.coroutines.delay

@Composable
internal fun VideoPlayerScreen(
    state: MpvPlayerState,
    progress: VideoPlaybackProgressState,
    mpvView: MuBoxMpvView,
    onClose: () -> Unit,
    onPlayPause: () -> Unit,
    onSeek: (Long) -> Unit,
    onSpeedSelected: (Double) -> Unit,
    onAudioTrackSelected: (Int) -> Unit,
    onSubtitleTrackSelected: (Int) -> Unit,
    onSubtitlesDisabled: () -> Unit,
    onScaleModeSelected: (VideoScaleMode) -> Unit,
    onDecoderModeSelected: (VideoDecoderMode) -> Unit,
    onAnime4KProfileSelected: (Anime4KProfile) -> Unit,
    onOrientationToggle: () -> Unit,
    onControlsLockedChanged: (Boolean) -> Unit,
    onVolumeDelta: (Int) -> Unit,
    onBrightnessDelta: (Int) -> Unit,
    onDoubleTapSeek: (Boolean) -> Unit,
    onHorizontalSeekStarted: () -> Unit,
    onHorizontalSeekFraction: (Float) -> Unit,
    onHorizontalSeekEnded: () -> Unit,
    onZoomDelta: (Float) -> Unit,
    onTemporarySpeedStarted: () -> Unit,
    onTemporarySpeedDelta: (Double) -> Unit,
    onTemporarySpeedEnded: () -> Unit,
    onClearHud: () -> Unit,
    mediaContext: VideoPlayerMediaContext,
    episodeQueue: VideoEpisodeQueue?,
    currentEpisodeIndex: Int,
    isEpisodeSwitching: Boolean,
    onPreviousEpisode: () -> Unit,
    onNextEpisode: () -> Unit,
    onEpisodeSelected: (Int) -> Unit,
    controlsAutoHideMillis: Int,
    proxyStatistics: VideoProxyStatistics?,
    proxyDebugInfoEnabled: Boolean,
    onConfigureSystemBars: () -> Unit,
    onRestoreSystemBars: () -> Unit,
) {
    DisposableEffect(Unit) {
        onConfigureSystemBars()
        onDispose {
            onRestoreSystemBars()
        }
    }

    var menuVisible by remember { mutableStateOf(false) }
    var episodePageVisible by remember { mutableStateOf(false) }
    var controlsVisible by remember { mutableStateOf(true) }
    var lockButtonVisible by remember { mutableStateOf(true) }
    var lockButtonRevealSignal by remember { mutableIntStateOf(0) }
    val controlsLocked = state.gestureState.controlsLocked

    BackHandler(enabled = episodePageVisible) {
        episodePageVisible = false
    }

    LaunchedEffect(
        controlsVisible,
        menuVisible,
        episodePageVisible,
        controlsLocked,
        controlsAutoHideMillis,
    ) {
        if (!controlsVisible || controlsLocked || menuVisible || episodePageVisible || controlsAutoHideMillis <= 0) {
            return@LaunchedEffect
        }
        delay(controlsAutoHideMillis.toLong())
        menuVisible = false
        controlsVisible = false
    }

    LaunchedEffect(controlsLocked) {
        if (controlsLocked) {
            menuVisible = false
            episodePageVisible = false
            controlsVisible = false
            lockButtonVisible = false
        } else {
            controlsVisible = true
            lockButtonVisible = true
        }
    }

    LaunchedEffect(lockButtonVisible, controlsLocked, lockButtonRevealSignal) {
        if (!controlsLocked || !lockButtonVisible) return@LaunchedEffect
        delay(PLAYER_LOCKED_BUTTON_AUTO_HIDE_MILLIS)
        lockButtonVisible = false
    }

    Surface(modifier = Modifier.fillMaxSize(), color = Color.Black) {
        Box(modifier = Modifier.fillMaxSize()) {
            AndroidView(
                factory = { mpvView },
                modifier = Modifier.fillMaxSize(),
            )

            if (controlsLocked) {
                LockedPlayerGestureOverlay(
                    onTap = {
                        lockButtonVisible = true
                        lockButtonRevealSignal += 1
                    },
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                PlayerGestureOverlay(
                    onVolumeDelta = onVolumeDelta,
                    onBrightnessDelta = onBrightnessDelta,
                    onDoubleTapSeek = onDoubleTapSeek,
                    onHorizontalSeekStarted = onHorizontalSeekStarted,
                    onHorizontalSeekFraction = onHorizontalSeekFraction,
                    onHorizontalSeekEnded = onHorizontalSeekEnded,
                    onZoomDelta = onZoomDelta,
                    onTemporarySpeedStarted = onTemporarySpeedStarted,
                    onTemporarySpeedDelta = onTemporarySpeedDelta,
                    onTemporarySpeedEnded = onTemporarySpeedEnded,
                    onClearHud = onClearHud,
                    onOverlayTap = {
                        menuVisible = false
                        episodePageVisible = false
                        controlsVisible = !controlsVisible
                    },
                    modifier = Modifier.fillMaxSize(),
                )
            }

            if (!controlsLocked && controlsVisible) {
                PlayerTopBar(
                    title = state.displayName,
                    source = mediaContext.source,
                    onClose = onClose,
                    onMenuClick = {
                        episodePageVisible = false
                        menuVisible = !menuVisible
                    },
                    showEpisodeButton = !episodeQueue?.episodes.isNullOrEmpty(),
                    onEpisodeClick = {
                        menuVisible = false
                        episodePageVisible = true
                    },
                    onOrientationToggle = onOrientationToggle,
                    modifier = Modifier
                        .align(Alignment.TopCenter),
                )
            }

            if ((controlsVisible && !controlsLocked) || (controlsLocked && lockButtonVisible)) {
                PlayerLockButton(
                    controlsLocked = controlsLocked,
                    onClick = {
                        val nextLocked = !controlsLocked
                        onControlsLockedChanged(nextLocked)
                        menuVisible = false
                        episodePageVisible = false
                        controlsVisible = !nextLocked
                        lockButtonVisible = !nextLocked
                    },
                    modifier = Modifier
                        .align(Alignment.CenterStart)
                        .padding(start = PLAYER_LOCK_BUTTON_START_PADDING_DP.dp),
                )
            }

            if (!controlsLocked && controlsVisible && menuVisible) {
                PlayerMenuPanel(
                    state = state,
                    mediaContext = mediaContext,
                    proxyStatistics = proxyStatistics,
                    proxyDebugInfoEnabled = proxyDebugInfoEnabled,
                    onDismiss = { menuVisible = false },
                    onSpeedSelected = onSpeedSelected,
                    onScaleModeSelected = onScaleModeSelected,
                    onDecoderModeSelected = onDecoderModeSelected,
                    onAnime4KProfileSelected = onAnime4KProfileSelected,
                    onAudioTrackSelected = onAudioTrackSelected,
                    onSubtitleTrackSelected = onSubtitleTrackSelected,
                    onSubtitlesDisabled = onSubtitlesDisabled,
                    modifier = Modifier
                        .align(Alignment.CenterEnd)
                        .padding(end = 16.dp),
                )
            }

            GestureHud(
                message = state.gestureState.hudMessage,
                onTimeout = onClearHud,
                modifier = Modifier.align(Alignment.Center),
            )

            if (!controlsLocked && controlsVisible) {
                PlayerCenterControls(
                    isPaused = state.isPaused,
                    hasPreviousEpisode = episodeQueue?.let { currentEpisodeIndex > 0 } == true,
                    hasNextEpisode = episodeQueue?.let { currentEpisodeIndex < it.episodes.lastIndex } == true,
                    isEpisodeSwitching = isEpisodeSwitching,
                    onPreviousEpisode = onPreviousEpisode,
                    onNextEpisode = onNextEpisode,
                    onPlayPause = {
                        controlsVisible = true
                        onPlayPause()
                    },
                    onSeekBackward = {
                        controlsVisible = true
                        onSeek((progress.positionMillis - SEEK_STEP_MILLIS).coerceAtLeast(0L))
                    },
                    onSeekForward = {
                        controlsVisible = true
                        onSeek(
                            seekForwardTargetMillis(
                                positionMillis = progress.positionMillis,
                                durationMillis = progress.durationMillis,
                            ),
                        )
                    },
                    modifier = Modifier.align(Alignment.Center),
                )
                PlayerBottomControls(
                    state = state,
                    progress = progress,
                    onSeek = {
                        controlsVisible = true
                        onSeek(it)
                    },
                    modifier = Modifier.align(Alignment.BottomCenter),
                )
            }

            if (!controlsLocked && episodePageVisible && episodeQueue != null) {
                EpisodeSelectionPage(
                    queue = episodeQueue,
                    currentEpisodeIndex = currentEpisodeIndex,
                    isSwitching = isEpisodeSwitching,
                    onDismiss = { episodePageVisible = false },
                    onEpisodeSelected = { index ->
                        episodePageVisible = false
                        controlsVisible = true
                        onEpisodeSelected(index)
                    },
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }
}
