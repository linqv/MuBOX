package org.mubox.reader.video.player

import org.mubox.reader.core.model.media.VideoSubtitleOpenRequest
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
        assertEquals(mapOf("force-media-title" to "Movie", "vid" to "auto"), engine.stringProperties)
    }

    @Test
    fun loadClearsObservedRuntimeDecoderState() {
        val controller = MpvController(FakeMpvEngine())
        controller.onActiveHwdecChanged("no")
        controller.onActiveVideoDecoderChanged("libdav1d")

        controller.load(uri = "content://media/other.mp4", displayName = "Other")

        assertEquals(null, controller.state.value.activeHwdec)
        assertEquals(null, controller.state.value.activeVideoDecoder)
    }

    @Test
    fun loadWithResumePositionRestoresTimePositionOnlyAfterFileLoaded() {
        val engine = FakeMpvEngine()
        val controller = MpvController(engine)

        controller.load("http://127.0.0.1:1234/stream/1", "movie.mkv", startPositionMillis = 92_500L)

        assertEquals(listOf(listOf("loadfile", "http://127.0.0.1:1234/stream/1")), engine.commands)
        assertEquals(92_500L, controller.state.value.positionMillis)

        controller.onDurationChanged(120.0)

        assertEquals(emptyMap<String, Double>(), engine.doubleProperties)

        controller.onPositionChanged(0.0)
        controller.onFileLoaded()

        assertEquals(92.5, engine.doubleProperties["time-pos"] ?: 0.0, 0.0)
        assertEquals(92_500L, controller.state.value.positionMillis)
        assertEquals(92_500L, controller.progress.value.positionMillis)

        controller.onFileLoaded()

        assertEquals(listOf(92.5), engine.doublePropertyHistory("time-pos"))
    }

    @Test
    fun loadWithSubtitlesAddsTracksAfterLoadfile() {
        val engine = FakeMpvEngine()
        val controller = MpvController(engine)

        controller.load(
            uri = "fd://10",
            displayName = "movie.mkv",
            subtitles = listOf(
                VideoSubtitleOpenRequest(uri = "fd://11", displayName = "movie.srt"),
                VideoSubtitleOpenRequest(uri = "fd://12", displayName = "movie.zh.ass"),
            ),
        )

        assertEquals(
            listOf(
                listOf("loadfile", "fd://10"),
                listOf("sub-add", "fd://11", "select", "movie.srt"),
                listOf("sub-add", "fd://12", "auto", "movie.zh.ass"),
            ),
            engine.commands,
        )
    }

    @Test
    fun loadReportsFileLoadedBeforeAddingSubtitles() {
        val engine = FakeMpvEngine().apply {
            commandFailures += listOf("sub-add", "fd://11", "select", "movie.srt")
        }
        val controller = MpvController(engine)
        var fileLoaded = false

        val result = runCatching {
            controller.load(
                uri = "fd://10",
                displayName = "movie.mkv",
                subtitles = listOf(VideoSubtitleOpenRequest(uri = "fd://11", displayName = "movie.srt")),
                onFileLoaded = {
                    fileLoaded = true
                },
            )
        }

        assertTrue(result.isFailure)
        assertTrue(fileLoaded)
        assertEquals(listOf(listOf("loadfile", "fd://10")), engine.commands)
    }

    @Test
    fun addSubtitlesSelectsFirstTrackAndKeepsAdditionalTracksAuto() {
        val engine = FakeMpvEngine()
        val controller = MpvController(engine)

        controller.addSubtitles(
            listOf(
                VideoSubtitleOpenRequest(uri = "fd://11", displayName = "movie.srt"),
                VideoSubtitleOpenRequest(uri = "fd://12", displayName = "movie.zh.ass"),
            ),
        )

        assertEquals(
            listOf(
                listOf("sub-add", "fd://11", "select", "movie.srt"),
                listOf("sub-add", "fd://12", "auto", "movie.zh.ass"),
            ),
            engine.commands,
        )
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
        assertEquals(12_500L, controller.progress.value.positionMillis)
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

    @Test
    fun playbackEndedMarksPositionAtDuration() {
        val controller = MpvController(FakeMpvEngine())

        controller.onDurationChanged(60.0)
        controller.onPositionChanged(58.0)
        controller.onPlaybackEnded()

        assertEquals(true, controller.state.value.isPaused)
        assertEquals(60_000L, controller.state.value.positionMillis)
    }

    @Test
    fun playbackEndedClearsPositionWhenDurationIsUnknown() {
        val controller = MpvController(FakeMpvEngine())

        controller.onPositionChanged(58.0)
        controller.onPlaybackEnded()

        assertEquals(true, controller.state.value.isPaused)
        assertEquals(0L, controller.state.value.positionMillis)
    }
}
