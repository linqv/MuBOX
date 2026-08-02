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
    fun `episode transition consumes expected stop and resumes only after file loaded`() = runTest {
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
            MPVNode.MapNode(mapOf("reason" to MPVNode.StringNode("stop"))),
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
    ): VideoPlayerSessionCoordinator {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        return VideoPlayerSessionCoordinator(
            scope = this,
            controller = controller,
            runtime = runtime,
            dispatchToMain = { action -> action() },
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
