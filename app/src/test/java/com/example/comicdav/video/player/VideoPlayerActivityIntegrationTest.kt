package com.example.comicdav.video.player

import com.example.comicdav.video.VideoSubtitleOpenRequest
import `is`.xyz.mpv.MPVNode
import java.util.concurrent.Executors
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class VideoPlayerActivityIntegrationTest {
    @Test
    fun playbackInputResolutionUsesTheConfiguredBackgroundDispatcher() {
        val dispatcher = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "video-input-resolver")
        }.asCoroutineDispatcher()
        try {
            runTest {
                var resolverThreadName: String? = null
                val coordinator = VideoPlaybackLoadCoordinator(
                    canLoad = { true },
                    resolutionDispatcher = dispatcher,
                    resolvePlaybackInput = {
                        resolverThreadName = Thread.currentThread().name
                        ResolvedPlaybackInput(ManagedPlaybackUri("file:///movie.mkv"), emptyList())
                    },
                    requestAudioFocus = { false },
                    startPlayback = { _, _, _ -> error("must not load") },
                    onAudioFocusDenied = {},
                    onFailure = { throw AssertionError("unexpected failure", it) },
                )

                coordinator.load(testLoadRequest())

                assertEquals("video-input-resolver", resolverThreadName)
            }
        } finally {
            dispatcher.close()
        }
    }

    @Test
    fun playbackLoadResolvesInputThenRequestsFocusAndTransfersResourcesToMpv() = runTest {
        val events = mutableListOf<String>()
        var videoCloseCount = 0
        var subtitleCloseCount = 0
        val input = ResolvedPlaybackInput(
            videoUri = ManagedPlaybackUri("fd://41") { videoCloseCount += 1 },
            subtitles = listOf(
                ResolvedSubtitlePlaybackUri(
                    uri = ManagedPlaybackUri("/cache/subtitles/episode.ass") {
                        subtitleCloseCount += 1
                    },
                    displayName = "episode.ass",
                ),
            ),
        )
        var capturedRequest: VideoPlaybackLoadRequest? = null
        var capturedInput: ResolvedPlaybackInput? = null
        var fileLoadedCallback: (() -> Unit)? = null
        val coordinator = VideoPlaybackLoadCoordinator(
            canLoad = { true },
            resolvePlaybackInput = {
                events += "resolve"
                input
            },
            requestAudioFocus = {
                events += "focus"
                true
            },
            startPlayback = { resolved, request, onFileLoaded ->
                events += "load"
                capturedInput = resolved
                capturedRequest = request
                fileLoadedCallback = onFileLoaded
            },
            onAudioFocusDenied = { events += "focus-denied" },
            onFailure = { events += "failure:${it.message}" },
        )
        val request = VideoPlaybackLoadRequest(
            uri = "content://videos/episode-1",
            displayName = "Episode 1",
            startPositionMillis = 42_000L,
            subtitles = listOf(
                VideoSubtitleOpenRequest(
                    uri = "content://subtitles/episode-1",
                    displayName = "episode.ass",
                ),
            ),
            isWebDav = false,
        )

        assertTrue(coordinator.load(request))

        assertEquals(listOf("resolve", "focus", "load"), events)
        assertEquals(request, capturedRequest)
        assertEquals("fd://41", capturedInput?.videoUri?.uri)
        assertEquals(
            listOf(VideoSubtitleOpenRequest("/cache/subtitles/episode.ass", "episode.ass")),
            capturedInput?.subtitleRequests(),
        )
        fileLoadedCallback?.invoke()
        input.closeIfUnused()
        assertEquals(0, videoCloseCount)
        assertEquals(0, subtitleCloseCount)
    }

    @Test
    fun deniedAudioFocusDoesNotLoadAndClosesResolvedResources() = runTest {
        val events = mutableListOf<String>()
        var closeCount = 0
        val coordinator = VideoPlaybackLoadCoordinator(
            canLoad = { true },
            resolvePlaybackInput = {
                events += "resolve"
                ResolvedPlaybackInput(
                    videoUri = ManagedPlaybackUri("fd://52") { closeCount += 1 },
                    subtitles = listOf(
                        ResolvedSubtitlePlaybackUri(
                            ManagedPlaybackUri("fd://53") { closeCount += 1 },
                            "subtitle.srt",
                        ),
                    ),
                )
            },
            requestAudioFocus = {
                events += "focus"
                false
            },
            startPlayback = { _, _, _ -> events += "load" },
            onAudioFocusDenied = { events += "focus-denied" },
            onFailure = { events += "failure" },
        )

        val loaded = coordinator.load(testLoadRequest())

        assertFalse(loaded)
        assertEquals(listOf("resolve", "focus", "focus-denied"), events)
        assertEquals(2, closeCount)
    }

    @Test
    fun playerBecomingUnavailableAfterResolutionClosesResourcesWithoutRequestingFocus() = runTest {
        var loadabilityChecks = 0
        var closeCount = 0
        var focusRequests = 0
        val coordinator = VideoPlaybackLoadCoordinator(
            canLoad = {
                loadabilityChecks += 1
                loadabilityChecks == 1
            },
            resolvePlaybackInput = {
                ResolvedPlaybackInput(ManagedPlaybackUri("fd://61") { closeCount += 1 }, emptyList())
            },
            requestAudioFocus = {
                focusRequests += 1
                true
            },
            startPlayback = { _, _, _ -> error("must not load") },
            onAudioFocusDenied = {},
            onFailure = { throw AssertionError("unexpected failure", it) },
        )

        assertFalse(coordinator.load(testLoadRequest()))
        assertEquals(0, focusRequests)
        assertEquals(1, closeCount)
    }

    @Test
    fun mpvLoadFailureReportsErrorAndReleasesUnconsumedDescriptors() = runTest {
        var closeCount = 0
        val failures = mutableListOf<String?>()
        val coordinator = VideoPlaybackLoadCoordinator(
            canLoad = { true },
            resolvePlaybackInput = {
                ResolvedPlaybackInput(ManagedPlaybackUri("fd://71") { closeCount += 1 }, emptyList())
            },
            requestAudioFocus = { true },
            startPlayback = { _, _, _ -> error("mpv load failed") },
            onAudioFocusDenied = {},
            onFailure = { failures += it.message },
        )

        assertFalse(coordinator.load(testLoadRequest()))
        assertEquals(listOf("mpv load failed"), failures)
        assertEquals(1, closeCount)
    }

    @Test
    fun typedMpvPropertyEventsUpdateControllerState() {
        val controller = MpvController(FakeMpvEngine())
        val router = MpvPropertyEventRouter(controller)

        router.route("pause", true)
        router.route("duration", 120.5)
        router.route("time-pos", 42.25)
        router.route("speed", 1.5)
        router.route("aid", 7L)
        router.route("sid", 0L)
        router.route("hwdec", "mediacodec-copy")
        router.route("hwdec-current", "mediacodec")
        router.route("current-tracks/video/decoder", "h264_mediacodec")
        router.route("vo", "gpu-next")
        router.route("gpu-api", "vulkan")
        router.route("video-params/aspect", 16.0 / 9.0)
        router.route("video-out-params", MPVNode.MapNode(mapOf("w" to MPVNode.IntNode(1920L))))

        val state = controller.state.value
        assertTrue(state.isPaused)
        assertEquals(120_500L, controller.progress.value.durationMillis)
        assertEquals(42_250L, controller.progress.value.positionMillis)
        assertEquals(1.5, state.playbackSpeed, 0.0)
        assertEquals(7, state.selectedAudioTrackId)
        assertEquals(null, state.selectedSubtitleTrackId)
        assertEquals("mediacodec-copy", state.currentHwdec)
        assertEquals("mediacodec", state.activeHwdec)
        assertEquals("h264_mediacodec", state.activeVideoDecoder)
        assertEquals("gpu-next", state.currentVideoOutput)
        assertEquals("vulkan", state.currentGpuApi)
        assertEquals(16.0 / 9.0, state.videoParams.aspectRatio ?: 0.0, 0.0)
        assertEquals(1920, state.videoOutParams.width)
    }

    @Test
    fun anime4KStartupCompatibilityFallsBackFromGpuNextWhenNotUsingVulkan() {
        val compatibility = anime4kStartupCompatibility(
            settings = Anime4KSettings(
                enabled = true,
                mode = Anime4KMode.A,
                quality = Anime4KQuality.FAST,
            ),
            requestedVideoOutputMode = VideoOutputMode.GPU_NEXT,
            gpuApiMode = GpuApiMode.AUTO,
        )

        assertEquals(VideoOutputMode.AUTO, compatibility.effectiveVideoOutputMode)
        assertEquals(
            "Anime4K 与 gpu-next(OpenGL) 不兼容，已为本次播放使用 gpu",
            compatibility.statusMessage,
        )
    }

    private fun testLoadRequest(): VideoPlaybackLoadRequest =
        VideoPlaybackLoadRequest(
            uri = "content://videos/test",
            displayName = "test.mkv",
            startPositionMillis = 0L,
            subtitles = emptyList(),
            isWebDav = false,
        )
}
