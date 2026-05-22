package com.example.comicdav.video.player

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class VideoPlayerActivityOrientationSourceTest {
    @Test
    fun playerActivityUsesOrientationSessionInsteadOfHardcodedSensor() {
        val source = playerActivitySourceFile().readText()

        assertTrue(source.contains("VideoPlayerOrientationSession(initialPlayerOrientationMode)"))
        assertTrue(source.contains("orientationSession.initialRequestedOrientation()"))
        assertFalse(source.contains("requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR"))
    }

    @Test
    fun playerActivityAppliesVideoParamsThroughOrientationSession() {
        val source = playerActivitySourceFile().readText()

        assertTrue(source.contains("state.videoParams.aspectRatio"))
        assertTrue(source.contains("state.videoOutParams.aspectRatio"))
        assertTrue(source.contains("state.videoOutParams.width"))
        assertTrue(source.contains("preferredVideoParamsForOrientation(state)"))
        assertTrue(source.contains("orientationSession.requestForVideoParams(orientationVideoParams)"))
    }

    @Test
    fun playerActivityObservesMpvAspectPropertiesForOrientation() {
        val activitySource = playerActivitySourceFile().readText()
        val viewSource = playerMpvViewSourceFile().readText()

        assertTrue(viewSource.contains("\"video-params/aspect\""))
        assertTrue(viewSource.contains("\"video-out-params/aspect\""))
        assertTrue(activitySource.contains("\"video-params/aspect\" -> controller.onVideoAspectChanged(value)"))
        assertTrue(activitySource.contains("\"video-out-params/aspect\" -> controller.onVideoOutAspectChanged(value)"))
    }

    @Test
    fun playerActivityDoesNotDeclareSensorOrientationInManifest() {
        val manifest = sequenceOf(
            File("src/main/AndroidManifest.xml"),
            File("app/src/main/AndroidManifest.xml"),
        ).first { it.isFile }.readText()

        assertFalse(manifest.contains("android:screenOrientation=\"sensor\""))
    }

    private fun playerActivitySourceFile(): File =
        sequenceOf(
            File("src/main/java/com/example/comicdav/video/player/VideoPlayerActivity.kt"),
            File("app/src/main/java/com/example/comicdav/video/player/VideoPlayerActivity.kt"),
        ).first { it.isFile }

    private fun playerMpvViewSourceFile(): File =
        sequenceOf(
            File("src/main/java/com/example/comicdav/video/player/MuBoxMpvView.kt"),
            File("app/src/main/java/com/example/comicdav/video/player/MuBoxMpvView.kt"),
        ).first { it.isFile }
}
