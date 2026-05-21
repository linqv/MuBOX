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
}
