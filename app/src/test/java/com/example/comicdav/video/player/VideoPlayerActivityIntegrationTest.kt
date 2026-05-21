package com.example.comicdav.video.player

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoPlayerActivityIntegrationTest {
    @Test
    fun activityResolvesLocalUriAndRequestsAudioFocusBeforeLoading() {
        val source = activitySourceFile().readText()

        assertTrue(source.contains("LocalVideoUriResolver(this).resolve(uri)"))
        assertTrue(source.contains("audioFocusController.request()"))
        assertTrue(source.contains("controller.load(playableUri, displayName)"))
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
