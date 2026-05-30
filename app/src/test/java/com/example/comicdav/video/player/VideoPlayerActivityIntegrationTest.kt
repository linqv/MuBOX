package com.example.comicdav.video.player

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoPlayerActivityIntegrationTest {
    @Test
    fun activityResolvesLocalUriAndRequestsAudioFocusBeforeLoading() {
        val source = activitySourceFile().readText()

        assertTrue(source.contains("withContext(Dispatchers.IO)"))
        assertTrue(source.contains("resolvePlaybackInput("))
        assertTrue(source.contains("audioFocusController.request()"))
        assertTrue(source.contains("controller.load("))
        assertTrue(source.contains("resolvedInput.videoUri.uri"))
        assertTrue(source.contains("startPositionMillis = startPositionMillis"))
        assertTrue(source.contains("subtitles = resolvedInput.subtitleRequests()"))
    }

    @Test
    fun activityPreparesMpvFromLoadJobAfterComposeCanAttachView() {
        val source = activitySourceFile().readText()
        val contentIndex = source.indexOf("setContent {")
        val prepareIndex = source.indexOf("if (!prepareMpv()) return@launch")

        assertTrue("VideoPlayerActivity should call setContent", contentIndex >= 0)
        assertTrue("VideoPlayerActivity should prepare mpv from the load coroutine", prepareIndex >= 0)
        assertTrue("Compose should be attached before async mpv preparation", contentIndex < prepareIndex)
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
    fun activityStartsProgressAutosaveOnlyAfterMpvLoadSucceeds() {
        val source = activitySourceFile().readText()
        val loadMpvIndex = source.indexOf("val loaded = loadMpv(")
        val autosaveIndex = source.indexOf("if (loaded) startPlaybackProgressAutoSave()")

        assertTrue("VideoPlayerActivity should capture whether mpv load succeeded", loadMpvIndex >= 0)
        assertTrue("VideoPlayerActivity should start autosave only after successful mpv load", autosaveIndex >= 0)
        assertTrue("autosave must not run before resume position is applied", loadMpvIndex < autosaveIndex)
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
    fun activityUsesDecorViewInsetsControllerForPlayerSystemBars() {
        val source = activitySourceFile().readText()

        assertTrue(source.contains("decorView.windowInsetsController"))
        assertTrue(!source.contains("window.insetsController"))
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
        assertTrue(viewSource.contains("MPVLib.observeProperty(\"hwdec-current\""))
        assertTrue(viewSource.contains("MPVLib.observeProperty(\"current-tracks/video/decoder\""))
        assertTrue(viewSource.contains("MPVLib.observeProperty(\"vo\""))
        assertTrue(viewSource.contains("MPVLib.observeProperty(\"gpu-api\""))
        assertTrue(activitySource.contains("controller.onTrackListChanged(value)"))
        assertTrue(activitySource.contains("controller.onAudioTrackChanged(value.toInt())"))
        assertTrue(activitySource.contains("controller.onSubtitleTrackChanged(value.toInt().takeIf { it > 0 })"))
        assertTrue(activitySource.contains("controller.onSpeedChanged(value)"))
        assertTrue(activitySource.contains("controller.onVideoParamsChanged(value)"))
        assertTrue(activitySource.contains("controller.onVideoOutParamsChanged(value)"))
        assertTrue(activitySource.contains("controller.onActiveHwdecChanged(value)"))
        assertTrue(activitySource.contains("controller.onActiveVideoDecoderChanged(value)"))
    }

    @Test
    fun screenExposesVisibleAlternativesForAdvancedPlaybackControls() {
        val source = videoPlayerPackageSource()

        assertTrue(source.contains("onSpeedSelected = controller::setPlaybackSpeed"))
        assertTrue(source.contains("onAudioTrackSelected = controller::selectAudioTrack"))
        assertTrue(source.contains("onSubtitleTrackSelected = controller::selectSubtitleTrack"))
        assertTrue(source.contains("onSubtitlesDisabled = controller::disableSubtitles"))
        assertTrue(!source.contains("onSubtitleDelayChanged = controller::adjustSubtitleDelay"))
        assertTrue(!source.contains("onAudioDelayChanged = controller::adjustAudioDelay"))
        assertTrue(source.contains("onScaleModeSelected = controller::setScaleMode"))
        assertTrue(source.contains("onDecoderModeSelected = controller::setDecoderMode"))
        assertTrue(!source.contains("onVideoOutputModeSelected = controller::setVideoOutputMode"))
        assertTrue(!source.contains("onGpuApiModeSelected = controller::setGpuApiMode"))
        assertTrue(source.contains("controller.setVideoOutputMode(initialVideoOutputMode)"))
        assertTrue(source.contains("controller.setGpuApiMode(initialGpuApiMode)"))
        assertTrue(source.contains("controller.setDecoderMode(initialVideoDecoderMode)"))
        assertTrue(source.contains("onControlsLockedChanged = controller::setControlsLocked"))
        assertTrue(source.contains("PlayerMenuPanel("))
        assertTrue(source.contains("PlayerOptionPanel.TRACKS"))
        assertTrue(source.contains("PlayerOptionPanel.INFO"))
        assertTrue(!source.contains("PlayerOptionPanel.DELAYS"))
        assertTrue(!source.contains("PlayerOptionPanel.QUEUE"))
        assertTrue(!source.contains("PlayerOptionPanel.SPEED"))
        assertTrue(!source.contains("PlayerOptionPanel.VIDEO"))
        assertTrue(source.contains("onOverlayTap"))
        assertTrue(source.contains("controlsAutoHideMillis"))
        assertTrue(source.contains("lockButtonRevealSignal"))
        assertTrue(source.contains("delay(PLAYER_LOCKED_BUTTON_AUTO_HIDE_MILLIS)"))
    }

    @Test
    fun screenExposesStatisticsInFloatingOptionPanel() {
        val source = videoPlayerPackageSource()

        assertTrue(source.contains("VideoPlayerMediaContext("))
        assertTrue(source.contains("buildVideoPlayerStatisticsSnapshot("))
        assertTrue(source.contains("StatisticsControls("))
        assertTrue(source.contains("Text(\"信息\""))
        assertTrue(source.contains("PlayerOptionPanel.INFO -> PlayerOptionPanelDescriptor(Icons.Filled.Info, \"播放信息\")"))
        assertTrue(source.contains("snapshot.redacted().debugLines()"))
    }

    @Test
    fun centerForwardControlDoesNotClampUnknownDurationToZero() {
        val source = videoPlayerPackageSource()

        assertTrue(source.contains("seekForwardTargetMillis("))
        assertTrue(!source.contains("state.positionMillis + SEEK_STEP_MILLIS).coerceAtMost(state.durationMillis)"))
    }

    @Test
    fun screenDoesNotExposePlaybackQueueInFloatingOptionPanel() {
        val source = activitySourceFile().readText()

        assertTrue(!source.contains("val playbackQueue = intent.playbackQueue()"))
        assertTrue(!source.contains("queue = playbackQueue"))
        assertTrue(!source.contains("QueueControls(queue = queue)"))
        assertTrue(!source.contains("queue?.previousItem()?.displayName"))
        assertTrue(!source.contains("queue?.currentItem?.displayName"))
        assertTrue(!source.contains("queue?.nextItem()?.displayName"))
    }

    @Test
    fun screenConnectsGestureOverlayToControllerGestureActions() {
        val source = videoPlayerPackageSource()

        assertTrue(source.contains("PlayerGestureOverlay("))
        assertTrue(source.contains("onVolumeDelta = controller::adjustGestureVolume"))
        assertTrue(source.contains("onBrightnessDelta = ::handleBrightnessGesture"))
        assertTrue(source.contains("controller.adjustGestureBrightness(deltaPercent)"))
        assertTrue(source.contains("applyScreenBrightnessPercent"))
        assertTrue(source.contains("onDoubleTapSeek = controller::handleDoubleTapSeek"))
        assertTrue(source.contains("onHorizontalSeekStarted = controller::beginHorizontalSwipeSeek"))
        assertTrue(source.contains("onHorizontalSeekFraction = controller::handleHorizontalSwipeSeek"))
        assertTrue(source.contains("onHorizontalSeekEnded = controller::endHorizontalSwipeSeek"))
        assertTrue(source.contains("onZoomDelta = controller::adjustGestureZoom"))
        assertTrue(source.contains("onTemporarySpeedStarted = { controller.beginTemporarySpeed(2.0) }"))
        assertTrue(source.contains("onTemporarySpeedDelta = controller::adjustTemporarySpeed"))
        assertTrue(source.contains("onTemporarySpeedEnded = controller::endTemporarySpeed"))
        assertTrue(source.contains("onClearHud = controller::clearGestureHud"))
        assertTrue(source.contains("detectTapGestures("))
        assertTrue(source.contains("playerGestureDragModeForPan(totalPan.x, totalPan.y)"))
        assertTrue(source.contains("PlayerGestureDragMode.HORIZONTAL_SEEK"))
        assertTrue(source.contains("PlayerGestureDragMode.VERTICAL_ADJUST"))
        assertTrue(source.contains("detectDragGesturesAfterLongPress("))
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

    private fun videoPlayerPackageSource(): String =
        videoPlayerPackageDir().listFiles()
            ?.filter { it.extension == "kt" }
            ?.joinToString("\n") { it.readText() }
            ?: activitySourceFile().readText()

    private fun videoPlayerPackageDir(): File =
        listOf(
            File("src/main/java/com/example/comicdav/video/player"),
            File("app/src/main/java/com/example/comicdav/video/player"),
        ).first { it.isDirectory }

    private fun mpvViewSourceFile(): File =
        listOf(
            File("src/main/java/com/example/comicdav/video/player/MuBoxMpvView.kt"),
            File("app/src/main/java/com/example/comicdav/video/player/MuBoxMpvView.kt"),
        ).first { it.isFile }
}
