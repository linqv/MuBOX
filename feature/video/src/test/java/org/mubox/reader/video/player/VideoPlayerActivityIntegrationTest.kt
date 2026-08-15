package org.mubox.reader.video.player

import org.mubox.reader.core.model.settings.Anime4KProfile
import org.mubox.reader.core.model.settings.GpuApiMode
import org.mubox.reader.core.model.settings.VideoOutputMode
import org.mubox.reader.core.model.media.VideoSubtitleOpenRequest
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
        router.route("container-fps", 23.976)
        router.route("aid", 7L)
        router.route("sid", 0L)
        router.route("hwdec", "mediacodec-copy")
        router.route("hwdec-current", "mediacodec")
        router.route("current-tracks/video/decoder", "h264_mediacodec")
        router.route("current-vo", "gpu-next")
        router.route("gpu-api", "vulkan")
        router.route("current-gpu-context", "androidvk")
        router.route("decoder-frame-drop-count", 2L)
        router.route("frame-drop-count", 3L)
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
        assertEquals("androidvk", state.currentGpuContext)
        assertEquals(2L, state.decoderDroppedFrames)
        assertEquals(3L, state.outputDroppedFrames)
        assertEquals(16.0 / 9.0, state.videoParams.aspectRatio ?: 0.0, 0.0)
        assertEquals(23.976, state.videoParams.frameRate ?: 0.0, 0.0)
        assertEquals(1920, state.videoOutParams.width)
    }

    @Test
    fun anime4KStartupCompatibilityKeepsGpuNextWithAutoGpuApi() {
        val compatibility = anime4kStartupCompatibility(
            profile = Anime4KProfile.EFFICIENCY,
            requestedVideoOutputMode = VideoOutputMode.GPU_NEXT,
            gpuApiMode = GpuApiMode.AUTO,
        )

        assertEquals(VideoOutputMode.GPU_NEXT, compatibility.effectiveVideoOutputMode)
        assertEquals(null, compatibility.statusMessage)
    }

    @Test
    fun backgroundNotificationUpdatesTrackForegroundServiceNotConfiguredMode() {
        assertFalse(shouldUpdateBackgroundPlaybackNotification(true, true))
        assertFalse(shouldUpdateBackgroundPlaybackNotification(true, false))
        assertFalse(shouldUpdateBackgroundPlaybackNotification(false, false))
        // 听视频把 NONE / RESUME_ON_RETURN 动态提升为后台播放后，
        // 前台服务运行即应刷新通知（睡眠定时器倒计时等）。
        assertTrue(shouldUpdateBackgroundPlaybackNotification(false, true))
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
