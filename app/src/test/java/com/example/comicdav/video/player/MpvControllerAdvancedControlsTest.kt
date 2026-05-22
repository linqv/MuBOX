package com.example.comicdav.video.player

import `is`.xyz.mpv.MPVNode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MpvControllerAdvancedControlsTest {
    @Test
    fun setPlaybackSpeedUpdatesStateAndMpvProperty() {
        val engine = AdvancedFakeMpvEngine()
        val controller = MpvController(engine)

        controller.setPlaybackSpeed(1.5)

        assertEquals(1.5, controller.state.value.playbackSpeed, 0.0)
        assertEquals(1.5, engine.doubleProperties.getValue("speed"), 0.0)
    }

    @Test
    fun temporarySpeedRestoresPreviousSpeedWhenReleased() {
        val engine = AdvancedFakeMpvEngine()
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
        val engine = AdvancedFakeMpvEngine()
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
        val engine = AdvancedFakeMpvEngine()
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
        val engine = AdvancedFakeMpvEngine()
        val controller = MpvController(engine)

        controller.adjustAudioDelay(-100)
        controller.resetAudioDelay()

        assertEquals(0L, controller.state.value.audioDelayMillis)
        assertEquals(listOf(-0.1, 0.0), engine.doublePropertyHistory("audio-delay"))
    }

    @Test
    fun decoderModesMapToExplicitHwdecStrategies() {
        val engine = AdvancedFakeMpvEngine()
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
        val engine = AdvancedFakeMpvEngine()
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
    fun scaleModesMapToAspectAndPanscanProperties() {
        val engine = AdvancedFakeMpvEngine()
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
        val controller = MpvController(AdvancedFakeMpvEngine())

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
        val controller = MpvController(AdvancedFakeMpvEngine())

        controller.onSpeedChanged(1.75)
        controller.onAudioTrackChanged(7)
        controller.onSubtitleTrackChanged(null)
        controller.onAudioDelayChanged(-0.2)
        controller.onHwdecChanged("mediacodec-copy")
        controller.onVoChanged("gpu-next")
        controller.onGpuApiChanged("vulkan")

        val state = controller.state.value
        assertEquals(1.75, state.playbackSpeed, 0.0)
        assertEquals(7, state.selectedAudioTrackId)
        assertEquals(null, state.selectedSubtitleTrackId)
        assertEquals(-200L, state.audioDelayMillis)
        assertEquals("mediacodec-copy", state.currentHwdec)
        assertEquals("gpu-next", state.currentVideoOutput)
        assertEquals("vulkan", state.currentGpuApi)
    }

    @Test
    fun observedVideoParamsIncludeRotationMetadata() {
        val controller = MpvController(AdvancedFakeMpvEngine())

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
        val controller = MpvController(AdvancedFakeMpvEngine())

        controller.onVideoAspectChanged(1080.0 / 1920.0)

        assertEquals(
            VideoParams(aspectRatio = 1080.0 / 1920.0),
            controller.state.value.videoParams,
        )
    }

    @Test
    fun lockControlsGesturesWithoutPausingPlayback() {
        val engine = AdvancedFakeMpvEngine()
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
}

private class AdvancedFakeMpvEngine : MpvEngine {
    val commands = mutableListOf<List<String>>()
    val stringProperties = mutableMapOf<String, String>()
    val intProperties = mutableMapOf<String, Int>()
    val doubleProperties = mutableMapOf<String, Double>()
    val booleanProperties = mutableMapOf<String, Boolean>()
    private val stringPropertyHistory = mutableMapOf<String, MutableList<String>>()
    private val intPropertyHistory = mutableMapOf<String, MutableList<Int>>()
    private val doublePropertyHistory = mutableMapOf<String, MutableList<Double>>()
    private val optionHistory = mutableMapOf<String, MutableList<String>>()

    override fun loadFile(uri: String) {
        commands += listOf("loadfile", uri)
    }

    override fun command(vararg args: String) {
        commands += args.toList()
    }

    override fun setPropertyString(name: String, value: String) {
        stringProperties[name] = value
        stringPropertyHistory.getOrPut(name) { mutableListOf() } += value
    }

    override fun setPropertyBoolean(name: String, value: Boolean) {
        booleanProperties[name] = value
    }

    override fun setPropertyInt(name: String, value: Int) {
        intProperties[name] = value
        intPropertyHistory.getOrPut(name) { mutableListOf() } += value
    }

    override fun setPropertyDouble(name: String, value: Double) {
        doubleProperties[name] = value
        doublePropertyHistory.getOrPut(name) { mutableListOf() } += value
    }

    override fun setOptionString(name: String, value: String) {
        optionHistory.getOrPut(name) { mutableListOf() } += value
    }

    override fun destroy() = Unit

    fun stringPropertyHistory(name: String): List<String> = stringPropertyHistory[name].orEmpty()

    fun intPropertyHistory(name: String): List<Int> = intPropertyHistory[name].orEmpty()

    fun doublePropertyHistory(name: String): List<Double> = doublePropertyHistory[name].orEmpty()

    fun optionHistory(name: String): List<String> = optionHistory[name].orEmpty()
}
