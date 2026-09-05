package org.mubox.reader.video.player

import `is`.xyz.mpv.MPVLib
import `is`.xyz.mpv.MPVNode
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class VideoPlayerSessionCoordinatorTest {
    @Test
    fun `prepare registers native observers in order and cleanup is exactly once`() = runTest {
        val runtime = RecordingMpvRuntime()
        val engine = FakeMpvEngine()
        val coordinator = createCoordinator(runtime, MpvController(engine))

        assertTrue(coordinator.prepare())
        assertEquals(
            listOf("copy-assets", "add-log-observer", "initialize", "attach-surface", "add-observer"),
            runtime.operations,
        )
        assertEquals(VideoPlayerSessionState.READY, coordinator.currentState)
        assertTrue(coordinator.canLoad())

        val cleanupOrder = mutableListOf<String>()
        coordinator.launchLoad { awaitCancellation() }
        assertTrue(coordinator.hasPendingLoad)
        assertTrue(
            coordinator.cleanup(
                onBeforeMpvCleanup = { cleanupOrder += "before" },
                onAfterMpvCleanup = {
                    assertEquals(1, engine.destroyCalls)
                    cleanupOrder += "after"
                },
            ),
        )

        assertFalse(coordinator.hasPendingLoad)
        assertEquals(VideoPlayerSessionState.CLOSED, coordinator.currentState)
        assertEquals(1, engine.destroyCalls)
        assertEquals(listOf("before", "after"), cleanupOrder)
        assertEquals(
            listOf(
                "copy-assets",
                "add-log-observer",
                "initialize",
                "attach-surface",
                "add-observer",
                "remove-observer",
                "remove-log-observer",
            ),
            runtime.operations,
        )

        assertFalse(coordinator.cleanup(onBeforeMpvCleanup = { cleanupOrder += "duplicate" }))
        assertEquals(1, engine.destroyCalls)
        assertEquals(listOf("before", "after"), cleanupOrder)
    }

    @Test
    fun `episode transition consumes normal old-file end and resumes only after file loaded`() = runTest {
        val runtime = RecordingMpvRuntime()
        val engine = FakeMpvEngine()
        val controller = MpvController(engine)
        var playbackEndedCalls = 0
        var playbackInterruptedCalls = 0
        val coordinator = createCoordinator(
            runtime = runtime,
            controller = controller,
            onPlaybackEnded = { playbackEndedCalls += 1 },
            onPlaybackInterrupted = { playbackInterruptedCalls += 1 },
        )
        assertTrue(coordinator.prepare())
        val observer = requireNotNull(runtime.observer)

        coordinator.beginEpisodeTransition()
        observer.event(
            MPVLib.MpvEvent.MPV_EVENT_END_FILE,
            // Some mpv/device combinations report replacement as a normal reason other than stop.
            MPVNode.MapNode(mapOf("reason" to MPVNode.StringNode("quit"))),
        )
        assertEquals(0, playbackEndedCalls)

        controller.setPaused(true)
        observer.event(
            MPVLib.MpvEvent.MPV_EVENT_FILE_LOADED,
            MPVNode.MapNode(emptyMap()),
        )
        assertEquals(false, engine.booleanProperties["pause"])

        observer.event(
            MPVLib.MpvEvent.MPV_EVENT_END_FILE,
            MPVNode.MapNode(mapOf("reason" to MPVNode.StringNode("eof"))),
        )
        assertEquals(1, playbackEndedCalls)

        observer.event(
            MPVLib.MpvEvent.MPV_EVENT_END_FILE,
            MPVNode.MapNode(
                mapOf(
                    "reason" to MPVNode.StringNode("error"),
                    "error" to MPVNode.StringNode("network"),
                ),
            ),
        )
        assertEquals(1, playbackInterruptedCalls)
    }

    @Test
    fun `episode transition preserves paused state when resume is not requested`() = runTest {
        val runtime = RecordingMpvRuntime()
        val engine = FakeMpvEngine()
        val controller = MpvController(engine)
        val coordinator = createCoordinator(runtime, controller)
        assertTrue(coordinator.prepare())

        controller.setPaused(true)
        coordinator.beginEpisodeTransition(resumePlayback = false)
        requireNotNull(runtime.observer).event(
            MPVLib.MpvEvent.MPV_EVENT_FILE_LOADED,
            MPVNode.MapNode(emptyMap()),
        )

        assertTrue(controller.state.value.isPaused)
        assertEquals(true, engine.booleanProperties["pause"])
    }

    @Test
    fun `eof reached drives keep-open completion once and rearms after seeking away`() = runTest {
        val runtime = RecordingMpvRuntime()
        val controller = MpvController(FakeMpvEngine())
        var playbackEndedCalls = 0
        val coordinator = createCoordinator(
            runtime = runtime,
            controller = controller,
            onPlaybackEnded = { playbackEndedCalls += 1 },
        )
        assertTrue(coordinator.prepare())
        val observer = requireNotNull(runtime.observer)

        observer.eventProperty("eof-reached", true)
        observer.eventProperty("eof-reached", true)

        assertEquals(1, playbackEndedCalls)
        assertTrue(controller.state.value.isPaused)

        observer.eventProperty("eof-reached", false)
        observer.eventProperty("eof-reached", true)

        assertEquals(2, playbackEndedCalls)
    }

    @Test
    fun `end-file after eof property does not dispatch completion twice`() = runTest {
        val runtime = RecordingMpvRuntime()
        var playbackEndedCalls = 0
        val coordinator = createCoordinator(
            runtime = runtime,
            controller = MpvController(FakeMpvEngine()),
            onPlaybackEnded = { playbackEndedCalls += 1 },
        )
        assertTrue(coordinator.prepare())
        val observer = requireNotNull(runtime.observer)

        observer.eventProperty("eof-reached", true)
        observer.event(
            MPVLib.MpvEvent.MPV_EVENT_END_FILE,
            MPVNode.MapNode(mapOf("reason" to MPVNode.StringNode("eof"))),
        )

        assertEquals(1, playbackEndedCalls)
    }

    @Test
    fun `episode transition consumes old-file end even when it arrives after new file loaded`() = runTest {
        val runtime = RecordingMpvRuntime()
        val controller = MpvController(FakeMpvEngine())
        var playbackEndedCalls = 0
        var playbackInterruptedCalls = 0
        var transitionCompletedCalls = 0
        val coordinator = createCoordinator(
            runtime = runtime,
            controller = controller,
            onPlaybackEnded = { playbackEndedCalls += 1 },
            onPlaybackInterrupted = { playbackInterruptedCalls += 1 },
        )
        assertTrue(coordinator.prepare())
        val observer = requireNotNull(runtime.observer)

        observer.event(
            MPVLib.MpvEvent.MPV_EVENT_START_FILE,
            MPVNode.MapNode(mapOf("playlist_entry_id" to MPVNode.IntNode(10L))),
        )
        coordinator.beginEpisodeTransition(
            onTransitionCompleted = { transitionCompletedCalls += 1 },
        )
        observer.event(
            MPVLib.MpvEvent.MPV_EVENT_START_FILE,
            MPVNode.MapNode(mapOf("playlist_entry_id" to MPVNode.IntNode(11L))),
        )
        observer.event(MPVLib.MpvEvent.MPV_EVENT_FILE_LOADED, MPVNode.MapNode(emptyMap()))
        assertEquals(0, transitionCompletedCalls)

        observer.event(
            MPVLib.MpvEvent.MPV_EVENT_END_FILE,
            MPVNode.MapNode(
                mapOf(
                    "reason" to MPVNode.StringNode("error"),
                    "error" to MPVNode.StringNode("old entry replaced"),
                    "playlist_entry_id" to MPVNode.IntNode(10L),
                ),
            ),
        )

        assertEquals(0, playbackEndedCalls)
        assertEquals(0, playbackInterruptedCalls)
        assertEquals(1, transitionCompletedCalls)

        observer.event(
            MPVLib.MpvEvent.MPV_EVENT_END_FILE,
            MPVNode.MapNode(
                mapOf(
                    "reason" to MPVNode.StringNode("eof"),
                    "playlist_entry_id" to MPVNode.IntNode(11L),
                ),
            ),
        )
        assertEquals(1, playbackEndedCalls)
    }

    @Test
    fun `queued old file-loaded event is not applied to replacement entry`() = runTest {
        val runtime = RecordingMpvRuntime()
        val engine = FakeMpvEngine()
        val controller = MpvController(engine)
        val pendingMainActions = ArrayDeque<() -> Unit>()
        var transitionCompletedCalls = 0
        val coordinator = createCoordinator(
            runtime = runtime,
            controller = controller,
            dispatchToMain = pendingMainActions::addLast,
        )
        assertTrue(coordinator.prepare())
        val observer = requireNotNull(runtime.observer)

        observer.event(
            MPVLib.MpvEvent.MPV_EVENT_START_FILE,
            MPVNode.MapNode(mapOf("playlist_entry_id" to MPVNode.IntNode(10L))),
        )
        // The native callback is received for the old entry, but its main-thread work is delayed.
        observer.event(MPVLib.MpvEvent.MPV_EVENT_FILE_LOADED, MPVNode.MapNode(emptyMap()))

        coordinator.beginEpisodeTransition(
            resumePlayback = false,
            onTransitionCompleted = { transitionCompletedCalls += 1 },
        )
        assertTrue(
            coordinator.load(
                uri = "fd://replacement",
                displayName = "replacement.mkv",
                startPositionMillis = 37_250L,
                subtitles = emptyList(),
                isWebDav = false,
            ),
        )
        observer.event(
            MPVLib.MpvEvent.MPV_EVENT_START_FILE,
            MPVNode.MapNode(mapOf("playlist_entry_id" to MPVNode.IntNode(11L))),
        )
        observer.event(
            MPVLib.MpvEvent.MPV_EVENT_END_FILE,
            MPVNode.MapNode(
                mapOf(
                    "reason" to MPVNode.StringNode("stop"),
                    "playlist_entry_id" to MPVNode.IntNode(10L),
                ),
            ),
        )

        pendingMainActions.removeFirst().invoke()
        assertFalse(engine.doubleProperties.containsKey("time-pos"))
        assertEquals(0, transitionCompletedCalls)

        pendingMainActions.removeFirst().invoke()
        observer.event(MPVLib.MpvEvent.MPV_EVENT_FILE_LOADED, MPVNode.MapNode(emptyMap()))
        pendingMainActions.removeFirst().invoke()

        assertEquals(37.25, engine.doubleProperties["time-pos"] ?: 0.0, 0.0)
        assertEquals(1, transitionCompletedCalls)
    }

    @Test
    fun `episode transition does not consume replacement-file error before old-file end`() = runTest {
        val runtime = RecordingMpvRuntime()
        val controller = MpvController(FakeMpvEngine())
        var playbackInterruptedCalls = 0
        var transitionCompletedCalls = 0
        val coordinator = createCoordinator(
            runtime = runtime,
            controller = controller,
            onPlaybackInterrupted = { playbackInterruptedCalls += 1 },
        )
        assertTrue(coordinator.prepare())
        val observer = requireNotNull(runtime.observer)

        observer.event(
            MPVLib.MpvEvent.MPV_EVENT_START_FILE,
            MPVNode.MapNode(mapOf("playlist_entry_id" to MPVNode.IntNode(20L))),
        )
        coordinator.beginEpisodeTransition(
            onTransitionCompleted = { transitionCompletedCalls += 1 },
        )
        observer.event(
            MPVLib.MpvEvent.MPV_EVENT_START_FILE,
            MPVNode.MapNode(mapOf("playlist_entry_id" to MPVNode.IntNode(21L))),
        )
        observer.event(
            MPVLib.MpvEvent.MPV_EVENT_END_FILE,
            MPVNode.MapNode(
                mapOf(
                    "reason" to MPVNode.StringNode("error"),
                    "file_error" to MPVNode.StringNode("HTTP 503"),
                    "playlist_entry_id" to MPVNode.IntNode(21L),
                ),
            ),
        )

        assertEquals(1, playbackInterruptedCalls)
        assertEquals(0, transitionCompletedCalls)

        observer.event(
            MPVLib.MpvEvent.MPV_EVENT_END_FILE,
            MPVNode.MapNode(
                mapOf(
                    "reason" to MPVNode.StringNode("stop"),
                    "playlist_entry_id" to MPVNode.IntNode(20L),
                ),
            ),
        )
        assertEquals(1, playbackInterruptedCalls)
        assertEquals(1, transitionCompletedCalls)
    }

    @Test
    fun `file loaded routes persisted position for initial playback`() = runTest {
        val runtime = RecordingMpvRuntime()
        val engine = FakeMpvEngine()
        val controller = MpvController(engine)
        val coordinator = createCoordinator(runtime, controller)
        assertTrue(coordinator.prepare())

        assertTrue(
            coordinator.load(
                uri = "fd://42",
                displayName = "movie.mkv",
                startPositionMillis = 37_250L,
                subtitles = emptyList(),
                isWebDav = false,
            ),
        )
        assertFalse(engine.doubleProperties.containsKey("time-pos"))

        requireNotNull(runtime.observer).event(
            MPVLib.MpvEvent.MPV_EVENT_FILE_LOADED,
            MPVNode.MapNode(emptyMap()),
        )

        assertEquals(37.25, engine.doubleProperties["time-pos"] ?: 0.0, 0.0)
    }

    @Test
    fun `background episode load bypasses detached surface`() = runTest {
        val runtime = RecordingMpvRuntime()
        val engine = FakeMpvEngine()
        val coordinator = createCoordinator(runtime, MpvController(engine))
        assertTrue(coordinator.prepare())

        assertTrue(
            coordinator.load(
                uri = "fd://43",
                displayName = "next.mkv",
                startPositionMillis = 0L,
                subtitles = emptyList(),
                isWebDav = false,
                requiresSurface = false,
            ),
        )

        assertEquals(listOf(false), engine.requiresSurfaceValues)
    }

    @Test
    fun `preparation failure rolls back registration and reports an error`() = runTest {
        val runtime = RecordingMpvRuntime(failInitialize = true)
        val engine = FakeMpvEngine()
        val controller = MpvController(engine)
        val coordinator = createCoordinator(runtime, controller)

        assertFalse(coordinator.prepare())

        assertEquals(VideoPlayerSessionState.NEW, coordinator.currentState)
        assertFalse(coordinator.canLoad())
        assertEquals("initialize failed", controller.state.value.errorMessage)
        assertEquals(
            listOf("copy-assets", "add-log-observer", "initialize", "remove-log-observer"),
            runtime.operations,
        )
        assertEquals(0, engine.destroyCalls)
    }

    @Test
    fun `shader diagnostics are routed through the session log observer`() = runTest {
        val diagnostics = mutableListOf<String>()
        val runtime = RecordingMpvRuntime()
        val coordinator = createCoordinator(
            runtime = runtime,
            controller = MpvController(FakeMpvEngine()),
            diagnostics = diagnostics,
        )
        assertTrue(coordinator.prepare())

        requireNotNull(runtime.logObserver).logMessage("vo/gpu-next", 2, " shader compile failed ")
        requireNotNull(runtime.logObserver).logMessage("demux", 2, "cache underrun")

        assertEquals(listOf("mpv[vo/gpu-next][2] shader compile failed"), diagnostics)
    }

    private fun kotlinx.coroutines.test.TestScope.createCoordinator(
        runtime: RecordingMpvRuntime,
        controller: MpvController,
        onPlaybackEnded: () -> Unit = {},
        onPlaybackInterrupted: () -> Unit = {},
        diagnostics: MutableList<String> = mutableListOf(),
        dispatchToMain: ((() -> Unit) -> Unit) = { action -> action() },
    ): VideoPlayerSessionCoordinator {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        return VideoPlayerSessionCoordinator(
            scope = this,
            controller = controller,
            runtime = runtime,
            dispatchToMain = dispatchToMain,
            isHostFinishing = { false },
            isHostInForeground = { true },
            resolvePlaybackInput = { request ->
                ResolvedPlaybackInput(
                    videoUri = ManagedPlaybackUri(request.uri),
                    subtitles = emptyList(),
                )
            },
            requestAudioFocus = { true },
            onPlaybackEnded = onPlaybackEnded,
            onPlaybackInterrupted = onPlaybackInterrupted,
            preparationDispatcher = dispatcher,
            resolutionDispatcher = dispatcher,
            logShaderDiagnostic = { message -> diagnostics += message },
        )
    }

    private class RecordingMpvRuntime(
        private val failInitialize: Boolean = false,
    ) : VideoPlayerMpvRuntime {
        val operations = mutableListOf<String>()
        var observer: MPVLib.EventObserver? = null
        var logObserver: MPVLib.LogObserver? = null

        override fun copyAssets() {
            operations += "copy-assets"
        }

        override fun addObserver(observer: MPVLib.EventObserver) {
            operations += "add-observer"
            this.observer = observer
        }

        override fun removeObserver(observer: MPVLib.EventObserver) {
            operations += "remove-observer"
            assertEquals(this.observer, observer)
            this.observer = null
        }

        override fun addLogObserver(observer: MPVLib.LogObserver) {
            operations += "add-log-observer"
            logObserver = observer
        }

        override fun removeLogObserver(observer: MPVLib.LogObserver) {
            operations += "remove-log-observer"
            assertEquals(logObserver, observer)
            logObserver = null
        }

        override fun initialize() {
            operations += "initialize"
            if (failInitialize) error("initialize failed")
        }

        override fun attachExistingSurfaceIfReady() {
            operations += "attach-surface"
        }
    }
}
