package com.example.comicdav.video.player

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ViewBackedMpvEngineTest {
    @Test
    fun viewBackedEngineUsesSurfaceAwareFileLoader() {
        val loader = FakeMpvFileLoader()
        val engine = ViewBackedMpvEngine(loader)
        var callbackCount = 0

        engine.loadFile("http://127.0.0.1:49152/stream/1") {
            callbackCount += 1
        }

        assertEquals(listOf("http://127.0.0.1:49152/stream/1"), loader.loadedUris)
        assertEquals(1, callbackCount)
    }

    @Test
    fun activityUsesViewBackedEngineSoLoadWaitsForSurfaceCreation() {
        val activitySource = activitySourceFile().readText()
        val engineSource = engineSourceFile().readText()

        assertTrue(activitySource.contains("MpvController(ViewBackedMpvEngine(mpvView))"))
        assertTrue(engineSource.contains("override fun loadFile(uri: String)"))
        assertTrue(engineSource.contains("view.playFileWhenReady(uri, afterLoadfile)"))
        assertFalse(engineSource.contains("MPVLib.command(\"loadfile\", uri, \"replace\""))
    }

    @Test
    fun muboxMpvViewLoadsImmediatelyWhenSurfaceAlreadyExists() {
        val source = mpvViewSourceFile().readText()

        assertTrue(source.contains("private var surfaceAttached = false"))
        assertTrue(source.contains("override fun surfaceCreated(holder: SurfaceHolder)"))
        assertTrue(source.contains("override fun surfaceDestroyed(holder: SurfaceHolder)"))
        assertTrue(source.contains("fun playFileWhenReady(uri: String, afterLoadfile: () -> Unit)"))
        assertTrue(source.contains("if (surfaceAttached)"))
        assertTrue(source.contains("MPVLib.command(\"loadfile\", uri)"))
        assertTrue(source.contains("flushPendingAfterLoadfileActions()"))
        assertTrue(source.contains("playFile(uri)"))
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

    private fun mpvViewSourceFile(): File =
        listOf(
            File("src/main/java/com/example/comicdav/video/player/MuBoxMpvView.kt"),
            File("app/src/main/java/com/example/comicdav/video/player/MuBoxMpvView.kt"),
        ).first { it.isFile }
}

private class FakeMpvFileLoader : MpvFileLoader {
    val loadedUris = mutableListOf<String>()

    override fun playFileWhenReady(uri: String, afterLoadfile: () -> Unit) {
        loadedUris += uri
        afterLoadfile()
    }

    override fun destroy() = Unit
}
