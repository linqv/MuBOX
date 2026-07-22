package com.example.comicdav.video.player

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.comicdav.video.VideoPlaybackMemoryBudget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class MuBoxMpvViewFactoryTest {
    @Test
    fun viewPropertiesUpdateTheStartupConfigurationUsedByMpv() {
        val view = newMpvView()

        view.mpvProfileMode = MpvProfileMode.LOW_LATENCY
        view.videoOutputMode = VideoOutputMode.GPU_NEXT
        view.gpuApiMode = GpuApiMode.VULKAN
        view.videoDecoderMode = VideoDecoderMode.HARDWARE_PLUS
        view.anime4kSettings = Anime4KSettings(enabled = true, mode = Anime4KMode.B)

        assertEquals(MpvProfileMode.LOW_LATENCY, view.mpvProfileMode)
        assertEquals(VideoOutputMode.GPU_NEXT, view.videoOutputMode)
        assertEquals(GpuApiMode.VULKAN, view.gpuApiMode)
        assertEquals(VideoDecoderMode.HARDWARE_PLUS, view.videoDecoderMode)
        assertEquals(Anime4KSettings(enabled = true, mode = Anime4KMode.B), view.anime4kSettings)
    }

    @Test
    fun viewStartupConfigurationDefaultsToSupportedRendererModes() {
        val view = newMpvView()

        assertEquals(MpvProfileMode.FAST, view.mpvProfileMode)
        assertEquals(VideoOutputMode.AUTO, view.videoOutputMode)
        assertEquals(GpuApiMode.AUTO, view.gpuApiMode)
        assertEquals(VideoDecoderMode.AUTO, view.videoDecoderMode)
        assertEquals(Anime4KSettings(), view.anime4kSettings)
        assertNull(view.anime4kManager)
    }

    @Test
    fun startupOptionsApplyProfileRendererDecoderAndAnime4KBeforeMediaLoad() {
        val api = RecordingMpvNativeApi()
        val events = api.events
        val settings = Anime4KSettings(
            enabled = true,
            mode = Anime4KMode.C_PLUS,
            quality = Anime4KQuality.HIGH,
        )
        val applier = MpvStartupOptionsApplier(
            nativeApi = api,
            setVideoOutput = { events += "vo:$it" },
            initializeAnime4K = { events += "anime4k:initialize" },
            anime4KShaderChain = {
                assertEquals(settings, it)
                "/files/shaders/a.glsl:/files/shaders/b.glsl"
            },
            memoryBudget = testPlaybackMemoryBudget,
        )

        applier.apply(
            MpvViewStartupConfiguration(
                profileMode = MpvProfileMode.HIGH_QUALITY,
                videoOutputMode = VideoOutputMode.GPU_NEXT,
                gpuApiMode = GpuApiMode.AUTO,
                videoDecoderMode = VideoDecoderMode.HARDWARE_PLUS,
                anime4kSettings = settings,
            ),
        )

        assertEquals(
            listOf(
                "option:profile=high-quality",
                "option:gpu-api=auto",
                "vo:gpu-next",
                "option:hwdec=mediacodec",
                "option:hwdec-codecs=all",
                "option:demuxer-max-bytes=16777216",
                "option:demuxer-max-back-bytes=16777216",
                "option:msg-level=all=warn",
                "anime4k:initialize",
                "option:opengl-pbo=yes",
                "option:opengl-early-flush=no",
                "option:vd-lavc-dr=yes",
                "option:glsl-shaders=/files/shaders/a.glsl:/files/shaders/b.glsl",
                "property:keep-open=true",
                "property:input-default-bindings=true",
            ),
            events,
        )
    }

    @Test
    fun vulkanAnime4KSkipsOpenGlOnlyTuning() {
        val api = RecordingMpvNativeApi()
        MpvStartupOptionsApplier(
            nativeApi = api,
            setVideoOutput = {},
            initializeAnime4K = {},
            anime4KShaderChain = { "/files/shaders/anime4k.glsl" },
            memoryBudget = testPlaybackMemoryBudget,
        ).apply(
            MpvViewStartupConfiguration(
                gpuApiMode = GpuApiMode.VULKAN,
                anime4kSettings = Anime4KSettings(enabled = true),
            ),
        )

        assertTrue(api.events.none { it.startsWith("option:opengl-") })
        assertTrue(api.events.contains("option:vd-lavc-dr=yes"))
        assertTrue(api.events.contains("option:glsl-shaders=/files/shaders/anime4k.glsl"))
    }

    private fun newMpvView(): MuBoxMpvView =
        MuBoxMpvView(
            context = ApplicationProvider.getApplicationContext<Context>(),
            attrs = Robolectric.buildAttributeSet().build(),
        )

    private companion object {
        val testPlaybackMemoryBudget = VideoPlaybackMemoryBudget(
            totalBytes = 48L * 1024L * 1024L,
            mpvForwardBytes = 16L * 1024L * 1024L,
            mpvBackwardBytes = 16L * 1024L * 1024L,
            proxyBytes = 16L * 1024L * 1024L,
        )
    }
}

internal class RecordingMpvNativeApi : MpvNativeApi {
    val events = mutableListOf<String>()

    override fun setOptionString(name: String, value: String) {
        events += "option:$name=$value"
    }

    override fun setPropertyBoolean(name: String, value: Boolean) {
        events += "property:$name=$value"
    }

    override fun observeProperty(name: String, format: Int) {
        events += "observe:$name=$format"
    }

    override fun command(vararg args: String) {
        events += "command:${args.joinToString(",")}"
    }
}
