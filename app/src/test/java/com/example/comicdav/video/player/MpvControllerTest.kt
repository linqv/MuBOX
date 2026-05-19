package com.example.comicdav.video.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MpvControllerTest {
    @Test
    fun loadSetsTitleAndDelegatesToEngine() {
        val engine = FakeMpvEngine()
        val controller = MpvController(engine)

        controller.load(uri = "content://media/movie.mp4", displayName = "Movie")

        assertEquals("Movie", controller.state.value.displayName)
        assertEquals(listOf(listOf("loadfile", "content://media/movie.mp4")), engine.commands)
        assertEquals(mapOf("force-media-title" to "Movie"), engine.stringProperties)
    }

    @Test
    fun pauseAndToggleUpdateEngineAndState() {
        val engine = FakeMpvEngine()
        val controller = MpvController(engine)

        controller.setPaused(true)
        controller.togglePlayPause()

        assertFalse(engine.booleanProperties.getValue("pause"))
        assertFalse(controller.state.value.isPaused)
    }

    @Test
    fun seekToClampsPositionAndSendsAbsoluteSeek() {
        val engine = FakeMpvEngine()
        val controller = MpvController(engine)
        controller.onDurationChanged(60.0)

        controller.seekTo(90_000L)

        assertEquals(60_000L, controller.state.value.positionMillis)
        assertEquals(listOf("seek", "60.0", "absolute"), engine.commands.last())
    }

    @Test
    fun observedPropertiesUpdateState() {
        val controller = MpvController(FakeMpvEngine())

        controller.onPauseChanged(true)
        controller.onDurationChanged(123.4)
        controller.onPositionChanged(12.5)
        controller.onError("Cannot open file")

        assertTrue(controller.state.value.isPaused)
        assertEquals(123_400L, controller.state.value.durationMillis)
        assertEquals(12_500L, controller.state.value.positionMillis)
        assertEquals("Cannot open file", controller.state.value.errorMessage)
    }

    @Test
    fun destroyStopsPlaybackAndDestroysEngineOnce() {
        val engine = FakeMpvEngine()
        val controller = MpvController(engine)

        controller.destroy()
        controller.destroy()

        assertEquals(true, engine.booleanProperties.getValue("pause"))
        assertEquals(listOf(listOf("stop"), listOf("quit")), engine.commands)
        assertEquals(1, engine.destroyCalls)
    }

    @Test
    fun destroyStillDestroysEngineWhenCleanupThrows() {
        val engine = FakeMpvEngine().apply {
            booleanPropertyFailures += "pause"
            commandFailures += listOf("stop")
            commandFailures += listOf("quit")
        }
        val controller = MpvController(engine)

        try {
            controller.destroy()
        } catch (_: RuntimeException) {
        }

        assertEquals(1, engine.destroyCalls)
    }

    @Test
    fun controlMethodsDoNotWriteEngineAfterDestroy() {
        val engine = FakeMpvEngine()
        val controller = MpvController(engine)

        controller.destroy()

        controller.setPaused(false)
        controller.togglePlayPause()
        controller.seekTo(10_000L)
        controller.load(uri = "content://media/other.mp4", displayName = "Other")

        assertEquals(mapOf("pause" to true), engine.booleanProperties)
        assertEquals(emptyMap<String, String>(), engine.stringProperties)
        assertEquals(listOf(listOf("stop"), listOf("quit")), engine.commands)
        assertEquals(emptyList<String>(), engine.loadedFiles)
        assertEquals(1, engine.destroyCalls)
    }

    @Test
    fun playbackEndedMarksPausedWithoutWritingEngine() {
        val engine = FakeMpvEngine()
        val controller = MpvController(engine)

        controller.onPlaybackEnded()

        assertTrue(controller.state.value.isPaused)
        assertEquals(emptyMap<String, Boolean>(), engine.booleanProperties)
        assertEquals(emptyList<List<String>>(), engine.commands)
    }
}

private class FakeMpvEngine : MpvEngine {
    val commands = mutableListOf<List<String>>()
    val loadedFiles = mutableListOf<String>()
    val stringProperties = mutableMapOf<String, String>()
    val booleanProperties = mutableMapOf<String, Boolean>()
    val commandFailures = mutableSetOf<List<String>>()
    val booleanPropertyFailures = mutableSetOf<String>()
    var destroyCalls = 0

    override fun loadFile(uri: String) {
        loadedFiles += uri
        commands += listOf("loadfile", uri)
    }

    override fun command(vararg args: String) {
        if (args.toList() in commandFailures) {
            throw RuntimeException("command failed: ${args.joinToString(" ")}")
        }
        commands += args.toList()
    }

    override fun setPropertyString(name: String, value: String) {
        stringProperties[name] = value
    }

    override fun setPropertyBoolean(name: String, value: Boolean) {
        if (name in booleanPropertyFailures) {
            throw RuntimeException("boolean property failed: $name")
        }
        booleanProperties[name] = value
    }

    override fun destroy() {
        destroyCalls += 1
    }
}
