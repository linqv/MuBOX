package com.example.comicdav.video.player

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoPlayerActivityIntegrationTest {
    @Test
    fun activityResolvesLocalUriAndRequestsAudioFocusBeforeLoading() {
        val source = activitySourceFile().readText()

        assertTrue(source.contains("localUriResolver.resolve(uri)"))
        assertTrue(source.contains("audioFocusController.request()"))
        assertTrue(source.contains("controller.load("))
        assertTrue(source.contains("playableUri"))
        assertTrue(source.contains("startPositionMillis = startPositionMillis"))
        assertTrue(source.contains("subtitles = playableSubtitles"))
    }

    @Test
    fun activityRegistersMpvSurfaceCallbackBeforeComposeAttachesView() {
        val source = activitySourceFile().readText()
        val prepareIndex = source.indexOf("val mpvPrepared = prepareMpv()")
        val contentIndex = source.indexOf("setContent {")

        assertTrue("VideoPlayerActivity should prepare mpv before setContent attaches SurfaceView", prepareIndex >= 0)
        assertTrue("VideoPlayerActivity should call setContent", contentIndex >= 0)
        assertTrue("mpv SurfaceHolder callback must be registered before AndroidView attaches", prepareIndex < contentIndex)
    }

    @Test
    fun resumePositionLoadCanFinishAfterSurfaceCreation() {
        val source = activitySourceFile().readText()
        val contentIndex = source.indexOf("setContent {")
        val loadPositionIndex = source.indexOf("playbackStateStore.loadPosition(key)")
        val loadMpvIndex = source.indexOf("loadMpv(")

        assertTrue("VideoPlayerActivity should call setContent", contentIndex >= 0)
        assertTrue("VideoPlayerActivity should load resume position", loadPositionIndex >= 0)
        assertTrue("VideoPlayerActivity should load mpv after resume lookup", loadMpvIndex >= 0)
        assertTrue("resume position lookup can run after SurfaceView is attached", contentIndex < loadPositionIndex)
        assertTrue("mpv load must wait for resume position", loadPositionIndex < loadMpvIndex)
    }

    @Test
    fun activityCancelsPendingLoadBeforeCleanup() {
        val source = activitySourceFile().readText()

        assertTrue(source.contains("private var loadJob: Job? = null"))
        assertTrue(source.contains("loadJob = activityScope.launch"))
        assertTrue(source.contains("cancelPendingLoad()"))
    }

    @Test
    fun activityHandlesEndFileWithoutWritingPauseBackToMpv() {
        val source = activitySourceFile().readText()

        assertTrue(source.contains("controller.onPlaybackEnded()"))
    }

    @Test
    fun activityObservesAdvancedMpvPlaybackProperties() {
        val activitySource = activitySourceFile().readText()
        val viewSource = mpvViewSourceFile().readText()

        assertTrue(viewSource.contains("MPVLib.observeProperty(\"track-list\""))
        assertTrue(viewSource.contains("MPVLib.observeProperty(\"aid\""))
        assertTrue(viewSource.contains("MPVLib.observeProperty(\"sid\""))
        assertTrue(viewSource.contains("MPVLib.observeProperty(\"speed\""))
        assertTrue(viewSource.contains("MPVLib.observeProperty(\"video-params\""))
        assertTrue(viewSource.contains("MPVLib.observeProperty(\"video-out-params\""))
        assertTrue(viewSource.contains("MPVLib.observeProperty(\"hwdec\""))
        assertTrue(viewSource.contains("MPVLib.observeProperty(\"vo\""))
        assertTrue(viewSource.contains("MPVLib.observeProperty(\"gpu-api\""))
        assertTrue(activitySource.contains("controller.onTrackListChanged(value)"))
        assertTrue(activitySource.contains("controller.onAudioTrackChanged(value.toInt())"))
        assertTrue(activitySource.contains("controller.onSubtitleTrackChanged(value.toInt().takeIf { it > 0 })"))
        assertTrue(activitySource.contains("controller.onSpeedChanged(value)"))
        assertTrue(activitySource.contains("controller.onVideoParamsChanged(value)"))
        assertTrue(activitySource.contains("controller.onVideoOutParamsChanged(value)"))
    }

    @Test
    fun screenExposesVisibleAlternativesForAdvancedPlaybackControls() {
        val source = activitySourceFile().readText()

        assertTrue(source.contains("onSpeedSelected = controller::setPlaybackSpeed"))
        assertTrue(source.contains("onAudioTrackSelected = controller::selectAudioTrack"))
        assertTrue(source.contains("onSubtitleTrackSelected = controller::selectSubtitleTrack"))
        assertTrue(source.contains("onSubtitlesDisabled = controller::disableSubtitles"))
        assertTrue(source.contains("onSubtitleDelayChanged = controller::adjustSubtitleDelay"))
        assertTrue(source.contains("onAudioDelayChanged = controller::adjustAudioDelay"))
        assertTrue(source.contains("onScaleModeSelected = controller::setScaleMode"))
        assertTrue(source.contains("onDecoderModeSelected = controller::setDecoderMode"))
        assertTrue(!source.contains("onVideoOutputModeSelected = controller::setVideoOutputMode"))
        assertTrue(!source.contains("onGpuApiModeSelected = controller::setGpuApiMode"))
        assertTrue(source.contains("controller.setVideoOutputMode(initialVideoOutputMode)"))
        assertTrue(source.contains("controller.setGpuApiMode(initialGpuApiMode)"))
        assertTrue(source.contains("controller.setDecoderMode(initialVideoDecoderMode)"))
        assertTrue(source.contains("onControlsLockedChanged = controller::setControlsLocked"))
        assertTrue(source.contains("PlayerSideControls("))
        assertTrue(source.contains("PlayerBottomQuickControls("))
        assertTrue(source.contains("PlayerOptionSheet("))
        assertTrue(source.contains("PlayerOptionPanel.TRACKS"))
        assertTrue(source.contains("PlayerOptionPanel.DELAYS"))
        assertTrue(source.contains("PlayerOptionPanel.INFO"))
        assertTrue(source.contains("PlayerOptionPanel.QUEUE"))
        assertTrue(!source.contains("PlayerOptionPanel.SPEED"))
        assertTrue(!source.contains("PlayerOptionPanel.VIDEO"))
        assertTrue(source.contains("onOverlayTap"))
        assertTrue(source.contains("controlsAutoHideMillis"))
    }

    @Test
    fun screenExposesStatisticsInFloatingOptionPanel() {
        val source = activitySourceFile().readText()

        assertTrue(source.contains("VideoPlayerMediaContext("))
        assertTrue(source.contains("buildVideoPlayerStatisticsSnapshot("))
        assertTrue(source.contains("StatisticsControls("))
        assertTrue(source.contains("PlayerOptionPanel.INFO -> \"信息\""))
        assertTrue(source.contains("PlayerOptionPanel.INFO -> PlayerOptionPanelDescriptor("))
        assertTrue(source.contains("contentDescription = \"播放信息\""))
        assertTrue(source.contains("snapshot.redacted().debugLines()"))
    }

    @Test
    fun screenShowsPlaybackQueueFromIntentInFloatingOptionPanel() {
        val source = activitySourceFile().readText()

        assertTrue(source.contains("val playbackQueue = intent.playbackQueue()"))
        assertTrue(source.contains("queue = playbackQueue"))
        assertTrue(source.contains("QueueControls(queue = queue)"))
        assertTrue(source.contains("queue?.previousItem()?.displayName"))
        assertTrue(source.contains("queue?.currentItem?.displayName"))
        assertTrue(source.contains("queue?.nextItem()?.displayName"))
    }

    @Test
    fun screenConnectsGestureOverlayToControllerGestureActions() {
        val source = activitySourceFile().readText()

        assertTrue(source.contains("PlayerGestureOverlay("))
        assertTrue(source.contains("onVolumeDelta = controller::adjustGestureVolume"))
        assertTrue(source.contains("onBrightnessDelta = ::handleBrightnessGesture"))
        assertTrue(source.contains("controller.adjustGestureBrightness(deltaPercent)"))
        assertTrue(source.contains("applyScreenBrightnessPercent"))
        assertTrue(source.contains("onDoubleTapSeek = controller::handleDoubleTapSeek"))
        assertTrue(source.contains("onZoomDelta = controller::adjustGestureZoom"))
        assertTrue(source.contains("onClearHud = controller::clearGestureHud"))
        assertTrue(source.contains("detectTapGestures("))
        assertTrue(source.contains("detectTransformGestures("))
        assertTrue(source.contains("GestureHud("))
        assertTrue(source.contains("LaunchedEffect(message)"))
        assertTrue(source.contains("delay(GESTURE_HUD_TIMEOUT_MILLIS)"))
    }

    @Test
    fun activityPausesAndReleasesAudioFocusWhenStoppedInBackground() {
        val source = activitySourceFile().readText()

        assertTrue(source.contains("override fun onStop()"))
        assertTrue(source.contains("playbackLifecyclePolicy.moveToBackground()"))
        assertTrue(source.contains("audioFocusController.abandon()"))
    }

    @Test
    fun activityCancelsBackgroundCleanupOnForegroundWithoutAutoResuming() {
        val source = activitySourceFile().readText()

        assertTrue(source.contains("override fun onStart()"))
        assertTrue(source.contains("playbackLifecyclePolicy.returnToForeground()"))
        assertTrue(source.contains("onBackgroundTimeoutAfterCleanup"))
    }

    private fun activitySourceFile(): File =
        listOf(
            File("src/main/java/com/example/comicdav/video/player/VideoPlayerActivity.kt"),
            File("app/src/main/java/com/example/comicdav/video/player/VideoPlayerActivity.kt"),
        ).first { it.isFile }

    private fun mpvViewSourceFile(): File =
        listOf(
            File("src/main/java/com/example/comicdav/video/player/MuBoxMpvView.kt"),
            File("app/src/main/java/com/example/comicdav/video/player/MuBoxMpvView.kt"),
        ).first { it.isFile }
}
