package com.example.comicdav.video.player

import com.example.comicdav.core.model.settings.Anime4KProfile
import com.example.comicdav.core.model.settings.GpuApiMode
import com.example.comicdav.core.model.settings.VideoDecoderMode
import com.example.comicdav.core.model.settings.VideoOutputMode
import `is`.xyz.mpv.MPVNode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class MpvControllerAdvancedControlsTest {
    @Test
    fun constructorInitializesAnime4KProfileAndStatus() {
        val controller = MpvController(
            engine = FakeMpvEngine(),
            anime4kShaderProvider = FixedAnime4KShaderProvider("chain-a"),
            initialAnime4KProfile = Anime4KProfile.EXTREME,
            initialAnime4KStatusMessage = "startup status",
        )

        val state = controller.state.value
        assertEquals(Anime4KProfile.EXTREME, state.anime4kProfile)
        assertEquals("startup status", state.statusMessage)
    }

    @Test
    fun selectingEfficiencyOnCompatibleRendererWritesShaderChainAndUpdatesState() {
        val engine = FakeMpvEngine()
        val controller = MpvController(
            engine = engine,
            anime4kShaderProvider = FixedAnime4KShaderProvider("chain-a"),
        )

        controller.setAnime4KProfile(Anime4KProfile.EFFICIENCY)

        val state = controller.state.value
        assertEquals(listOf("chain-a"), engine.stringPropertyHistory("glsl-shaders"))
        assertEquals(Anime4KProfile.EFFICIENCY, state.anime4kProfile)
        assertEquals(null, state.statusMessage)
    }

    @Test
    fun selectingOffClearsShaders() {
        val engine = FakeMpvEngine()
        val controller = MpvController(
            engine = engine,
            anime4kShaderProvider = FixedAnime4KShaderProvider("chain-a"),
            initialAnime4KProfile = Anime4KProfile.EXTREME,
        )

        controller.setAnime4KProfile(Anime4KProfile.OFF)

        val state = controller.state.value
        assertEquals(listOf(""), engine.stringPropertyHistory("glsl-shaders"))
        assertEquals(Anime4KProfile.OFF, state.anime4kProfile)
        assertEquals(null, state.statusMessage)
    }

    @Test
    fun switchingAnime4KProfilesWritesOnlyShaderPropertyOnCompatibleRenderer() {
        val engine = FakeMpvEngine()
        val provider = RecordingAnime4KShaderProvider()
        val controller = MpvController(
            engine = engine,
            anime4kShaderProvider = provider,
            initialAnime4KProfile = Anime4KProfile.EFFICIENCY,
        )

        controller.setAnime4KProfile(Anime4KProfile.EXTREME)

        val state = controller.state.value
        assertEquals(
            listOf("chain-MODE_C_A_HIGH"),
            engine.stringPropertyHistory("glsl-shaders"),
        )
        assertEquals(emptyList<String>(), engine.optionHistory("vo"))
        assertEquals(Anime4KProfile.EXTREME, state.anime4kProfile)
        assertEquals(
            listOf(Anime4KPipeline.MODE_C_A_HIGH),
            provider.requests,
        )
    }

    @Test
    fun selectingAutoWaitsForVideoParamsThenAppliesResolvedPipelineOnce() {
        val engine = FakeMpvEngine()
        val provider = RecordingAnime4KShaderProvider()
        val controller = MpvController(
            engine = engine,
            anime4kShaderProvider = provider,
        )

        controller.onContainerFrameRateChanged(24.0)
        controller.setAnime4KProfile(Anime4KProfile.AUTO)
        val params = videoParamsNode(width = 1920, height = 1080)
        controller.onVideoParamsChanged(params)
        controller.onVideoParamsChanged(params)

        assertEquals(
            listOf("", "chain-MODE_A_BALANCED"),
            engine.stringPropertyHistory("glsl-shaders"),
        )
        assertEquals(listOf(Anime4KPipeline.MODE_A_BALANCED), provider.requests)
        assertEquals(Anime4KProfile.AUTO, controller.state.value.anime4kProfile)
        assertEquals(Anime4KPipeline.MODE_A_BALANCED, controller.state.value.anime4kPipeline)
    }

    @Test
    fun automaticPipelineDoesNotChangeAfterOutputDrops() {
        val engine = FakeMpvEngine()
        val provider = RecordingAnime4KShaderProvider()
        val controller = MpvController(
            engine = engine,
            anime4kShaderProvider = provider,
        )
        controller.onContainerFrameRateChanged(24.0)
        controller.setAnime4KProfile(Anime4KProfile.AUTO)
        controller.onVideoParamsChanged(videoParamsNode(width = 1280, height = 720))

        controller.onOutputDroppedFramesChanged(0)
        controller.onOutputDroppedFramesChanged(100)

        assertEquals(Anime4KProfile.AUTO, controller.state.value.anime4kProfile)
        assertEquals(Anime4KPipeline.MODE_B_BALANCED, controller.state.value.anime4kPipeline)
        assertEquals(100L, controller.state.value.outputDroppedFrames)
        assertEquals(null, controller.state.value.statusMessage)
        assertEquals(listOf(Anime4KPipeline.MODE_B_BALANCED), provider.requests)
        assertEquals(
            listOf("", "chain-MODE_B_BALANCED"),
            engine.stringPropertyHistory("glsl-shaders"),
        )
    }

    @Test
    fun automaticPipelineKeepsAutoSelectedWhileSkippingHdr() {
        val engine = FakeMpvEngine()
        val provider = RecordingAnime4KShaderProvider()
        val controller = MpvController(
            engine = engine,
            anime4kShaderProvider = provider,
        )
        controller.onContainerFrameRateChanged(24.0)
        controller.setAnime4KProfile(Anime4KProfile.AUTO)

        controller.onVideoParamsChanged(
            videoParamsNode(width = 1920, height = 1080, gamma = "pq"),
        )

        assertEquals(Anime4KProfile.AUTO, controller.state.value.anime4kProfile)
        assertEquals(null, controller.state.value.anime4kPipeline)
        assertTrue(controller.state.value.statusMessage.orEmpty().contains("HDR"))
        assertEquals(emptyList<Anime4KPipeline>(), provider.requests)
    }

    @Test
    fun automaticSelectionCapturesInitialVideoParamsOnly() {
        val provider = RecordingAnime4KShaderProvider()
        val controller = MpvController(
            engine = FakeMpvEngine(),
            anime4kShaderProvider = provider,
        )
        controller.onContainerFrameRateChanged(24.0)
        controller.setAnime4KProfile(Anime4KProfile.AUTO)
        controller.onVideoParamsChanged(videoParamsNode(width = 1920, height = 1080))

        controller.onContainerFrameRateChanged(60.0)
        controller.onVideoParamsChanged(videoParamsNode(width = 1280, height = 720))

        assertEquals(Anime4KPipeline.MODE_A_BALANCED, controller.state.value.anime4kPipeline)
        assertEquals(listOf(Anime4KPipeline.MODE_A_BALANCED), provider.requests)
        assertEquals(60.0, controller.state.value.videoParams.frameRate ?: 0.0, 0.0)
        assertEquals(1280, controller.state.value.videoParams.width)
    }

    @Test
    fun loadingAnotherVideoAllowsAutoToCaptureAnew() {
        val engine = FakeMpvEngine()
        val provider = RecordingAnime4KShaderProvider()
        val controller = MpvController(
            engine = engine,
            anime4kShaderProvider = provider,
        )
        controller.onContainerFrameRateChanged(24.0)
        controller.setAnime4KProfile(Anime4KProfile.AUTO)
        controller.onVideoParamsChanged(videoParamsNode(width = 1920, height = 1080))

        controller.load(uri = "file:///next.mkv", displayName = "next")
        controller.onContainerFrameRateChanged(24.0)
        controller.onVideoParamsChanged(videoParamsNode(width = 1280, height = 720))

        assertEquals(Anime4KPipeline.MODE_B_BALANCED, controller.state.value.anime4kPipeline)
        assertEquals(
            listOf(
                Anime4KPipeline.MODE_A_BALANCED,
                Anime4KPipeline.MODE_B_BALANCED,
            ),
            provider.requests,
        )
    }

    @Test
    fun anime4KRuntimeSwitchOnGpuNextWithAutoApiAppliesShadersWithoutChangingVo() {
        val engine = FakeMpvEngine()
        val controller = MpvController(
            engine = engine,
            anime4kShaderProvider = FixedAnime4KShaderProvider("chain-a"),
        )
        controller.setStartupRendererState(
            videoOutputMode = VideoOutputMode.GPU_NEXT,
            gpuApiMode = GpuApiMode.AUTO,
            decoderMode = VideoDecoderMode.AUTO,
        )

        controller.setAnime4KProfile(Anime4KProfile.EFFICIENCY)

        val state = controller.state.value
        assertEquals(listOf("chain-a"), engine.stringPropertyHistory("glsl-shaders"))
        assertEquals(emptyList<String>(), engine.optionHistory("vo"))
        assertEquals(Anime4KProfile.EFFICIENCY, state.anime4kProfile)
        assertEquals(null, state.statusMessage)
    }

    @Test
    fun observedGpuContextIsTrackedSeparatelyFromRequestedGpuApi() {
        val controller = MpvController(FakeMpvEngine())
        controller.setStartupRendererState(
            videoOutputMode = VideoOutputMode.GPU_NEXT,
            gpuApiMode = GpuApiMode.AUTO,
            decoderMode = VideoDecoderMode.AUTO,
        )
        controller.onGpuContextChanged("androidvk")

        val state = controller.state.value
        assertEquals("auto", state.currentGpuApi)
        assertEquals("androidvk", state.currentGpuContext)
    }

    @Test
    fun anime4KProfileChangesStayEnabledOnGpuNextOpenGl() {
        val engine = FakeMpvEngine()
        val controller = MpvController(
            engine = engine,
            anime4kShaderProvider = FixedAnime4KShaderProvider("chain-a"),
        )
        controller.setStartupRendererState(
            videoOutputMode = VideoOutputMode.GPU_NEXT,
            gpuApiMode = GpuApiMode.AUTO,
            decoderMode = VideoDecoderMode.AUTO,
        )

        controller.setAnime4KProfile(Anime4KProfile.EFFICIENCY)
        controller.setAnime4KProfile(Anime4KProfile.EXTREME)

        val state = controller.state.value
        assertEquals(listOf("chain-a", "chain-a"), engine.stringPropertyHistory("glsl-shaders"))
        assertEquals(Anime4KProfile.EXTREME, state.anime4kProfile)
        assertEquals(null, state.statusMessage)
    }

    @Test
    fun emptyAnime4KShaderChainDisablesRuntimeStateAndSetsStatus() {
        val engine = FakeMpvEngine()
        val controller = MpvController(
            engine = engine,
            anime4kShaderProvider = FixedAnime4KShaderProvider(""),
        )

        controller.setAnime4KProfile(Anime4KProfile.EFFICIENCY)

        val state = controller.state.value
        assertEquals(listOf(""), engine.stringPropertyHistory("glsl-shaders"))
        assertEquals(Anime4KProfile.OFF, state.anime4kProfile)
        assertNotNull(state.statusMessage)
    }

    @Test
    fun setPlaybackSpeedUpdatesStateAndMpvProperty() {
        val engine = FakeMpvEngine()
        val controller = MpvController(engine)

        controller.setPlaybackSpeed(1.5)

        assertEquals(1.5, controller.state.value.playbackSpeed, 0.0)
        assertEquals(1.5, engine.doubleProperties.getValue("speed"), 0.0)
    }

    @Test
    fun temporarySpeedRestoresPreviousSpeedWhenReleased() {
        val engine = FakeMpvEngine()
        val controller = MpvController(engine)
        controller.setPlaybackSpeed(1.25)

        controller.beginTemporarySpeed(2.0)
        controller.endTemporarySpeed()

        assertEquals(1.25, controller.state.value.playbackSpeed, 0.0)
        assertFalse(controller.state.value.gestureState.isTemporarySpeedActive)
        assertEquals(listOf(1.25, 2.0, 1.25), engine.doublePropertyHistory("speed"))
    }

    @Test
    fun temporarySpeedCanBeAdjustedWhileHeldAndStillRestoresPreviousSpeed() {
        val engine = FakeMpvEngine()
        val controller = MpvController(engine)
        controller.setPlaybackSpeed(1.25)

        controller.beginTemporarySpeed(2.0)
        controller.adjustTemporarySpeed(0.25)
        controller.adjustTemporarySpeed(-0.5)
        controller.endTemporarySpeed()

        assertEquals(1.25, controller.state.value.playbackSpeed, 0.0)
        assertFalse(controller.state.value.gestureState.isTemporarySpeedActive)
        assertEquals(listOf(1.25, 2.0, 2.25, 1.75, 1.25), engine.doublePropertyHistory("speed"))
    }

    @Test
    fun audioAndSubtitleTrackSelectionUseAidAndSidProperties() {
        val engine = FakeMpvEngine()
        val controller = MpvController(engine)

        controller.selectAudioTrack(3)
        controller.selectSubtitleTrack(5)
        controller.disableSubtitles()

        assertEquals(3, engine.intProperties.getValue("aid"))
        assertEquals(listOf(5), engine.intPropertyHistory("sid"))
        assertEquals("no", engine.stringProperties.getValue("sid"))
        assertEquals(3, controller.state.value.selectedAudioTrackId)
        assertEquals(null, controller.state.value.selectedSubtitleTrackId)
    }

    @Test
    fun audioDelayControlsUseSmallStepsAndResetToZero() {
        val engine = FakeMpvEngine()
        val controller = MpvController(engine)

        controller.adjustAudioDelay(-100)
        controller.resetAudioDelay()

        assertEquals(0L, controller.state.value.audioDelayMillis)
        assertEquals(listOf(-0.1, 0.0), engine.doublePropertyHistory("audio-delay"))
    }

    @Test
    fun decoderModesMapToExplicitHwdecStrategies() {
        val engine = FakeMpvEngine()
        val controller = MpvController(engine)

        controller.setDecoderMode(VideoDecoderMode.AUTO)
        controller.setDecoderMode(VideoDecoderMode.SOFTWARE)
        controller.setDecoderMode(VideoDecoderMode.HARDWARE)
        controller.setDecoderMode(VideoDecoderMode.HARDWARE_PLUS)

        assertEquals(
            listOf("mediacodec,mediacodec-copy,no", "no", "mediacodec-copy", "mediacodec"),
            engine.stringPropertyHistory("hwdec"),
        )
        assertEquals(VideoDecoderMode.HARDWARE_PLUS, controller.state.value.decoderMode)
    }

    @Test
    fun videoOutputAndGpuApiModesAreIndependentMpvOptions() {
        val engine = FakeMpvEngine()
        val controller = MpvController(engine)

        controller.setVideoOutputMode(VideoOutputMode.AUTO)
        controller.setVideoOutputMode(VideoOutputMode.GPU_NEXT)
        controller.setGpuApiMode(GpuApiMode.AUTO)
        controller.setGpuApiMode(GpuApiMode.VULKAN)

        assertEquals(listOf("gpu", "gpu-next"), engine.optionHistory("vo"))
        assertEquals(listOf("auto", "vulkan"), engine.optionHistory("gpu-api"))
        assertEquals(VideoOutputMode.GPU_NEXT, controller.state.value.videoOutputMode)
        assertEquals(GpuApiMode.VULKAN, controller.state.value.gpuApiMode)
    }

    @Test
    fun startupRendererStateUpdatesStateWithoutWritingMpvOptions() {
        val engine = FakeMpvEngine()
        val controller = MpvController(engine)

        controller.setStartupRendererState(
            videoOutputMode = VideoOutputMode.GPU_NEXT,
            gpuApiMode = GpuApiMode.VULKAN,
            decoderMode = VideoDecoderMode.HARDWARE_PLUS,
        )

        val state = controller.state.value
        assertEquals(VideoOutputMode.GPU_NEXT, state.videoOutputMode)
        assertEquals(GpuApiMode.VULKAN, state.gpuApiMode)
        assertEquals(VideoDecoderMode.HARDWARE_PLUS, state.decoderMode)
        assertEquals("gpu-next", state.currentVideoOutput)
        assertEquals("vulkan", state.currentGpuApi)
        assertEquals("mediacodec", state.currentHwdec)
        assertEquals(emptyList<String>(), engine.optionHistory("vo"))
        assertEquals(emptyList<String>(), engine.optionHistory("gpu-api"))
        assertEquals(emptyList<String>(), engine.stringPropertyHistory("hwdec"))
    }

    @Test
    fun scaleModesMapToAspectAndPanscanProperties() {
        val engine = FakeMpvEngine()
        val controller = MpvController(engine)

        controller.setScaleMode(VideoScaleMode.FIT)
        controller.setScaleMode(VideoScaleMode.FILL)
        controller.setScaleMode(VideoScaleMode.ORIGINAL)
        controller.setScaleMode(VideoScaleMode.RATIO_16_9)
        controller.setScaleMode(VideoScaleMode.RATIO_4_3)

        assertEquals(listOf(-1.0, -1.0, -1.0, 16.0 / 9.0, 4.0 / 3.0), engine.doublePropertyHistory("video-aspect-override"))
        assertEquals(listOf(0.0, 1.0, 0.0, 0.0, 0.0), engine.doublePropertyHistory("panscan"))
        assertEquals(listOf(0.0), engine.doublePropertyHistory("video-zoom"))
        assertEquals(VideoScaleMode.RATIO_4_3, controller.state.value.scaleMode)
    }

    @Test
    fun observedTrackListSeparatesAudioSubtitleAndExternalTracks() {
        val controller = MpvController(FakeMpvEngine())

        controller.onTrackListChanged(
            MPVNode.ArrayNode(
                arrayOf(
                    trackNode(id = 1, type = "audio", title = "Japanese", lang = "ja", selected = true),
                    trackNode(id = 2, type = "audio", title = "English", lang = "en"),
                    trackNode(id = 3, type = "sub", title = "内封字幕", lang = "zh"),
                    trackNode(id = 4, type = "sub", title = "movie.zh.srt", lang = "zh", external = true),
                ),
            ),
        )

        assertEquals(listOf(1, 2), controller.state.value.audioTracks.map { it.id })
        assertEquals(listOf(3, 4), controller.state.value.subtitleTracks.map { it.id })
        assertTrue(controller.state.value.hasMultipleSubtitleChoices)
        assertEquals("movie.zh.srt", controller.state.value.subtitleTracks.last().title)
        assertTrue(controller.state.value.subtitleTracks.last().isExternal)
    }

    @Test
    fun observedPropertiesUpdateAdvancedState() {
        val controller = MpvController(FakeMpvEngine())

        controller.onSpeedChanged(1.75)
        controller.onAudioTrackChanged(7)
        controller.onSubtitleTrackChanged(null)
        controller.onAudioDelayChanged(-0.2)
        controller.onHwdecChanged("mediacodec-copy")
        controller.onActiveHwdecChanged("no")
        controller.onActiveVideoDecoderChanged("libdav1d")
        controller.onVoChanged("gpu-next")
        controller.onGpuApiChanged("vulkan")
        controller.onGpuContextChanged("androidvk")
        controller.onDecoderDroppedFramesChanged(2)
        controller.onOutputDroppedFramesChanged(3)

        val state = controller.state.value
        assertEquals(1.75, state.playbackSpeed, 0.0)
        assertEquals(7, state.selectedAudioTrackId)
        assertEquals(null, state.selectedSubtitleTrackId)
        assertEquals(-200L, state.audioDelayMillis)
        assertEquals("mediacodec-copy", state.currentHwdec)
        assertEquals("no", state.activeHwdec)
        assertEquals("libdav1d", state.activeVideoDecoder)
        assertEquals("gpu-next", state.currentVideoOutput)
        assertEquals("vulkan", state.currentGpuApi)
        assertEquals("androidvk", state.currentGpuContext)
        assertEquals(2L, state.decoderDroppedFrames)
        assertEquals(3L, state.outputDroppedFrames)
    }

    @Test
    fun observedPositionUpdatesProgressWithoutReplacingFullPlayerState() {
        val controller = MpvController(FakeMpvEngine())
        val stateBeforePosition = controller.state.value

        controller.onPositionChanged(42.0)

        assertEquals(42_000L, controller.progress.value.positionMillis)
        assertSame(stateBeforePosition, controller.state.value)
    }

    @Test
    fun observedVolumeSeedsGestureVolumeBaseline() {
        val controller = MpvController(FakeMpvEngine())

        controller.onVolumeChanged(100.0)

        assertEquals(100, controller.state.value.gestureState.volumePercent)
    }

    @Test
    fun observedVideoParamsIncludeRotationMetadata() {
        val controller = MpvController(FakeMpvEngine())

        controller.onVideoParamsChanged(
            MPVNode.MapNode(
                mapOf(
                    "w" to MPVNode.IntNode(1920L),
                    "h" to MPVNode.IntNode(1080L),
                    "rotate" to MPVNode.IntNode(90L),
                ),
            ),
        )

        assertEquals(
            VideoParams(width = 1920, height = 1080, rotationDegrees = 90),
            controller.state.value.videoParams,
        )
    }

    @Test
    fun observedVideoAspectUpdatesStateWithoutDimensionMap() {
        val controller = MpvController(FakeMpvEngine())

        controller.onVideoAspectChanged(1080.0 / 1920.0)

        assertEquals(
            VideoParams(aspectRatio = 1080.0 / 1920.0),
            controller.state.value.videoParams,
        )
    }

    @Test
    fun lockControlsGesturesWithoutPausingPlayback() {
        val engine = FakeMpvEngine()
        val controller = MpvController(engine)

        controller.setControlsLocked(true)

        assertTrue(controller.state.value.gestureState.controlsLocked)
        assertEquals(emptyMap<String, Boolean>(), engine.booleanProperties)
    }

    private fun trackNode(
        id: Int,
        type: String,
        title: String,
        lang: String,
        selected: Boolean = false,
        external: Boolean = false,
    ): MPVNode =
        MPVNode.MapNode(
            mapOf(
                "id" to MPVNode.IntNode(id.toLong()),
                "type" to MPVNode.StringNode(type),
                "title" to MPVNode.StringNode(title),
                "lang" to MPVNode.StringNode(lang),
                "selected" to MPVNode.BooleanNode(selected),
                "external" to MPVNode.BooleanNode(external),
            ),
        )

    private fun videoParamsNode(
        width: Int,
        height: Int,
        gamma: String? = null,
    ): MPVNode {
        val values = mutableMapOf<String, MPVNode>(
            "w" to MPVNode.IntNode(width.toLong()),
            "h" to MPVNode.IntNode(height.toLong()),
        )
        gamma?.let { values["gamma"] = MPVNode.StringNode(it) }
        return MPVNode.MapNode(values)
    }

    private class FixedAnime4KShaderProvider(
        private val chain: String,
    ) : Anime4KShaderProvider {
        override fun shaderChain(pipeline: Anime4KPipeline): String = chain
    }

    private class RecordingAnime4KShaderProvider : Anime4KShaderProvider {
        val requests = mutableListOf<Anime4KPipeline>()

        override fun shaderChain(pipeline: Anime4KPipeline): String {
            requests += pipeline
            return "chain-${pipeline.name}"
        }
    }
}
