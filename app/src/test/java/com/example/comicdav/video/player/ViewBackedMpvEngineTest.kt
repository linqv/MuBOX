package com.example.comicdav.video.player

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class ViewBackedMpvEngineTest {
    @Test
    fun activityUsesViewBackedEngineSoLoadWaitsForSurfaceCreation() {
        val activitySource = activitySourceFile().readText()
        val engineSource = engineSourceFile().readText()

        assertTrue(activitySource.contains("MpvController(ViewBackedMpvEngine(mpvView))"))
        assertTrue(engineSource.contains("override fun loadFile(uri: String)"))
        assertTrue(engineSource.contains("view.playFile(uri)"))
    }

    private fun activitySourceFile(): File =
        listOf(
            File("src/main/java/com/example/comicdav/video/player/VideoPlayerActivity.kt"),
            File("app/src/main/java/com/example/comicdav/video/player/VideoPlayerActivity.kt"),
        ).first { it.isFile }

    private fun engineSourceFile(): File =
        listOf(
            File("src/main/java/com/example/comicdav/video/player/MpvController.kt"),
            File("app/src/main/java/com/example/comicdav/video/player/MpvController.kt"),
        ).first { it.isFile }
}
