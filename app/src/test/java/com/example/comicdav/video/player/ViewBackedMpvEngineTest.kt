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

    @Test
    fun muboxMpvViewAttachesSurfaceThatAlreadyExistsWhenMpvIsInitializedLate() {
        val source = mpvViewSourceFile().readText()
        val activitySource = activitySourceFile().readText()

        assertTrue(source.contains("fun attachExistingSurfaceIfReady()"))
        assertTrue(source.contains("holder.surface"))
        assertTrue(source.contains("surface.isValid"))
        assertTrue(source.contains("surfaceCreated(holder)"))
        assertTrue(source.contains("PixelFormat.RGBA_8888"))
        assertTrue(source.contains("surfaceChanged(holder, PixelFormat.RGBA_8888, frame.width(), frame.height())"))

        val initializeIndex = activitySource.indexOf("mpvView.initialize(filesDir.path, cacheDir.path)")
        val attachIndex = activitySource.indexOf("mpvView.attachExistingSurfaceIfReady()")

        assertTrue(initializeIndex >= 0)
        assertTrue(attachIndex >= 0)
        assertTrue(initializeIndex < attachIndex)
    }

    @Test
    fun muboxMpvViewAppliesConfiguredMpvProfileBeforeOtherVideoOptions() {
        val source = mpvViewSourceFile().readText()

        assertTrue(source.contains("var mpvProfileMode: MpvProfileMode = MpvProfileMode.FAST"))
        val profileOptionIndex = source.indexOf("MPVLib.setOptionString(\"profile\", mpvProfileMode.profile)")
        val gpuApiIndex = source.indexOf("MPVLib.setOptionString(\"gpu-api\", gpuApiMode.gpuApi)")
        val videoOutputIndex = source.indexOf("setVo(videoOutputMode.videoOutput)")
        val videoDecoderIndex = source.indexOf("MPVLib.setOptionString(\"hwdec\", videoDecoderMode.hwdec)")

        assertTrue(profileOptionIndex >= 0)
        assertTrue(gpuApiIndex >= 0)
        assertTrue(videoOutputIndex >= 0)
        assertTrue(videoDecoderIndex >= 0)
        assertTrue(profileOptionIndex < gpuApiIndex)
        assertTrue(profileOptionIndex < videoOutputIndex)
        assertTrue(profileOptionIndex < videoDecoderIndex)
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
