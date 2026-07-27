package com.example.comicdav.video.player

import com.example.comicdav.core.model.settings.Anime4KProfile
import com.example.comicdav.core.model.settings.GpuApiMode
import com.example.comicdav.core.model.settings.MpvProfileMode
import com.example.comicdav.core.model.settings.VideoDecoderMode
import com.example.comicdav.core.model.settings.VideoOutputMode
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.comicdav.video.VideoPlaybackMemoryBudget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
        view.anime4kProfile = Anime4KProfile.EXTREME

        assertEquals(MpvProfileMode.LOW_LATENCY, view.mpvProfileMode)
        assertEquals(VideoOutputMode.GPU_NEXT, view.videoOutputMode)
        assertEquals(GpuApiMode.VULKAN, view.gpuApiMode)
        assertEquals(VideoDecoderMode.HARDWARE_PLUS, view.videoDecoderMode)
        assertEquals(Anime4KProfile.EXTREME, view.anime4kProfile)
    }

    @Test
    fun viewStartupConfigurationDefaultsToSupportedRendererModes() {
        val view = newMpvView()

        assertEquals(MpvProfileMode.FAST, view.mpvProfileMode)
        assertEquals(VideoOutputMode.AUTO, view.videoOutputMode)
        assertEquals(GpuApiMode.AUTO, view.gpuApiMode)
        assertEquals(VideoDecoderMode.AUTO, view.videoDecoderMode)
        assertEquals(Anime4KProfile.OFF, view.anime4kProfile)
        assertNull(view.anime4kManager)
    }

    @Test
    fun startupOptionsApplyProfileRendererDecoderAndAnime4KBeforeMediaLoad() {
        val api = RecordingMpvNativeApi()
        val events = api.events
        val profile = Anime4KProfile.EXTREME
        val applier = MpvStartupOptionsApplier(
            nativeApi = api,
            setVideoOutput = { events += "vo:$it" },
            initializeAnime4K = { events += "anime4k:initialize" },
            anime4KShaderChain = {
                assertEquals(profile, it)
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
                anime4kProfile = profile,
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
                anime4kProfile = Anime4KProfile.EFFICIENCY,
            ),
        )

        assertTrue(api.events.none { it.startsWith("option:opengl-") })
        assertTrue(api.events.contains("option:vd-lavc-dr=yes"))
        assertTrue(api.events.contains("option:glsl-shaders=/files/shaders/anime4k.glsl"))
    }

    @Test
    fun legacyGpuAnime4KKeepsItsDirectRenderingTuning() {
        val api = RecordingMpvNativeApi()
        MpvStartupOptionsApplier(
            nativeApi = api,
            setVideoOutput = {},
            initializeAnime4K = {},
            anime4KShaderChain = { "/files/shaders/anime4k.glsl" },
            memoryBudget = testPlaybackMemoryBudget,
        ).apply(
            MpvViewStartupConfiguration(
                videoOutputMode = VideoOutputMode.AUTO,
                gpuApiMode = GpuApiMode.AUTO,
                anime4kProfile = Anime4KProfile.EFFICIENCY,
            ),
        )

        assertTrue(api.events.contains("option:opengl-pbo=yes"))
        assertTrue(api.events.contains("option:opengl-early-flush=no"))
        assertTrue(api.events.contains("option:vd-lavc-dr=yes"))
        assertTrue(api.events.contains("option:glsl-shaders=/files/shaders/anime4k.glsl"))
    }

    @Test
    fun automaticAnime4KPreparesRendererBeforeVideoParamsResolveItsChain() {
        val api = RecordingMpvNativeApi()
        MpvStartupOptionsApplier(
            nativeApi = api,
            setVideoOutput = {},
            initializeAnime4K = {},
            anime4KShaderChain = {
                assertEquals(Anime4KProfile.AUTO, it)
                ""
            },
            memoryBudget = testPlaybackMemoryBudget,
        ).apply(
            MpvViewStartupConfiguration(
                videoOutputMode = VideoOutputMode.AUTO,
                gpuApiMode = GpuApiMode.AUTO,
                anime4kProfile = Anime4KProfile.AUTO,
            ),
        )

        assertTrue(api.events.contains("option:opengl-pbo=yes"))
        assertTrue(api.events.contains("option:opengl-early-flush=no"))
        assertTrue(api.events.contains("option:vd-lavc-dr=yes"))
        assertTrue(api.events.none { it.startsWith("option:glsl-shaders=") })
    }

    @Test
    fun shaderDiagnosticFilterKeepsShaderLogsAndRejectsUnrelatedWarnings() {
        assertTrue(isMpvShaderDiagnostic("vo/gpu-next", "GLSL shader compilation failed"))
        assertTrue(isMpvShaderDiagnostic("libplacebo", "hook warning"))
        assertFalse(isMpvShaderDiagnostic("demux", "cache underrun"))
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
