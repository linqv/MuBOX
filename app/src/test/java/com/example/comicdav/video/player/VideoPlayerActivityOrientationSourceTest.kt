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

        assertTrue(source.contains("LaunchedEffect(state.videoParams.width, state.videoParams.height)"))
        assertTrue(source.contains("orientationSession.requestForVideoParams(state.videoParams)"))
    }

    private fun playerActivitySourceFile(): File =
        sequenceOf(
            File("src/main/java/com/example/comicdav/video/player/VideoPlayerActivity.kt"),
            File("app/src/main/java/com/example/comicdav/video/player/VideoPlayerActivity.kt"),
        ).first { it.isFile }
}
