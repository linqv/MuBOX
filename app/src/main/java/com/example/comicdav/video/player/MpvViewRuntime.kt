package com.example.comicdav.video.player

import `is`.xyz.mpv.MPVLib
import com.example.comicdav.video.VideoPlaybackMemoryBudget

/**
 * Small boundary around mpv's static JNI API. Keeping option construction on this side of the
 * boundary lets local tests verify the actual calls without loading a native library.
 */
internal interface MpvNativeApi {
    fun setOptionString(name: String, value: String)
    fun setPropertyBoolean(name: String, value: Boolean)
    fun observeProperty(name: String, format: Int)
    fun command(vararg args: String)
}

internal object RealMpvNativeApi : MpvNativeApi {
    override fun setOptionString(name: String, value: String) {
        MPVLib.setOptionString(name, value)
    }

    override fun setPropertyBoolean(name: String, value: Boolean) {
        MPVLib.setPropertyBoolean(name, value)
    }

    override fun observeProperty(name: String, format: Int) {
        MPVLib.observeProperty(name, format)
    }

    override fun command(vararg args: String) {
        MPVLib.command(*args)
    }
}

internal data class MpvViewStartupConfiguration(
    val profileMode: MpvProfileMode = MpvProfileMode.FAST,
    val videoOutputMode: VideoOutputMode = VideoOutputMode.AUTO,
    val gpuApiMode: GpuApiMode = GpuApiMode.AUTO,
    val videoDecoderMode: VideoDecoderMode = VideoDecoderMode.AUTO,
    val anime4kSettings: Anime4KSettings = Anime4KSettings(),
)

internal class MpvStartupOptionsApplier(
    private val nativeApi: MpvNativeApi,
    private val setVideoOutput: (String) -> Unit,
    private val initializeAnime4K: () -> Unit,
    private val anime4KShaderChain: (Anime4KSettings) -> String,
    private val memoryBudget: VideoPlaybackMemoryBudget,
) {
    fun apply(configuration: MpvViewStartupConfiguration) {
        nativeApi.setOptionString("profile", configuration.profileMode.profile)
        nativeApi.setOptionString("gpu-api", configuration.gpuApiMode.gpuApi)
        setVideoOutput(configuration.videoOutputMode.videoOutput)
        nativeApi.setOptionString("hwdec", configuration.videoDecoderMode.hwdec)
        nativeApi.setOptionString("hwdec-codecs", "all")
        nativeApi.setOptionString("demuxer-max-bytes", memoryBudget.mpvForwardBytes.toString())
        nativeApi.setOptionString("demuxer-max-back-bytes", memoryBudget.mpvBackwardBytes.toString())
        nativeApi.setOptionString("msg-level", "all=warn")

        initializeAnime4K()
        val shaderChain = if (
            configuration.anime4kSettings.enabled &&
            configuration.anime4kSettings.mode != Anime4KMode.OFF
        ) {
            anime4KShaderChain(configuration.anime4kSettings)
        } else {
            ""
        }
        if (shaderChain.isNotBlank()) {
            if (configuration.videoOutputMode == VideoOutputMode.AUTO) {
                if (configuration.gpuApiMode != GpuApiMode.VULKAN) {
                    nativeApi.setOptionString("opengl-pbo", "yes")
                    nativeApi.setOptionString("opengl-early-flush", "no")
                }
                nativeApi.setOptionString("vd-lavc-dr", "yes")
            }
            nativeApi.setOptionString("glsl-shaders", shaderChain)
        }
        nativeApi.setPropertyBoolean("keep-open", true)
        nativeApi.setPropertyBoolean("input-default-bindings", true)
    }
}

internal data class MpvObservedProperty(
    val name: String,
    val format: Int,
)

internal val mpvObservedPlaybackProperties: List<MpvObservedProperty> = listOf(
    MpvObservedProperty("pause", MPVLib.MpvFormat.MPV_FORMAT_FLAG),
    MpvObservedProperty("duration", MPVLib.MpvFormat.MPV_FORMAT_DOUBLE),
    MpvObservedProperty("time-pos", MPVLib.MpvFormat.MPV_FORMAT_DOUBLE),
    MpvObservedProperty("core-idle", MPVLib.MpvFormat.MPV_FORMAT_FLAG),
    MpvObservedProperty("track-list", MPVLib.MpvFormat.MPV_FORMAT_NODE_ARRAY),
    MpvObservedProperty("aid", MPVLib.MpvFormat.MPV_FORMAT_INT64),
    MpvObservedProperty("sid", MPVLib.MpvFormat.MPV_FORMAT_INT64),
    MpvObservedProperty("speed", MPVLib.MpvFormat.MPV_FORMAT_DOUBLE),
    MpvObservedProperty("volume", MPVLib.MpvFormat.MPV_FORMAT_DOUBLE),
    MpvObservedProperty("audio-delay", MPVLib.MpvFormat.MPV_FORMAT_DOUBLE),
    MpvObservedProperty("video-params", MPVLib.MpvFormat.MPV_FORMAT_NODE_MAP),
    MpvObservedProperty("video-out-params", MPVLib.MpvFormat.MPV_FORMAT_NODE_MAP),
    MpvObservedProperty("video-params/aspect", MPVLib.MpvFormat.MPV_FORMAT_DOUBLE),
    MpvObservedProperty("video-out-params/aspect", MPVLib.MpvFormat.MPV_FORMAT_DOUBLE),
    MpvObservedProperty("hwdec", MPVLib.MpvFormat.MPV_FORMAT_STRING),
    MpvObservedProperty("hwdec-current", MPVLib.MpvFormat.MPV_FORMAT_STRING),
    MpvObservedProperty("current-tracks/video/decoder", MPVLib.MpvFormat.MPV_FORMAT_STRING),
    MpvObservedProperty("vo", MPVLib.MpvFormat.MPV_FORMAT_STRING),
    MpvObservedProperty("current-vo", MPVLib.MpvFormat.MPV_FORMAT_STRING),
    MpvObservedProperty("gpu-api", MPVLib.MpvFormat.MPV_FORMAT_STRING),
    MpvObservedProperty("current-gpu-context", MPVLib.MpvFormat.MPV_FORMAT_STRING),
    MpvObservedProperty("decoder-frame-drop-count", MPVLib.MpvFormat.MPV_FORMAT_INT64),
    MpvObservedProperty("frame-drop-count", MPVLib.MpvFormat.MPV_FORMAT_INT64),
)

internal fun observeMpvPlaybackProperties(nativeApi: MpvNativeApi) {
    mpvObservedPlaybackProperties.forEach { property ->
        nativeApi.observeProperty(property.name, property.format)
    }
}

internal fun isMpvShaderDiagnostic(prefix: String, text: String): Boolean {
    val message = "$prefix $text".lowercase()
    return SHADER_DIAGNOSTIC_MARKERS.any(message::contains)
}

private val SHADER_DIAGNOSTIC_MARKERS = listOf(
    "anime4k",
    "glsl",
    "shader",
    "libplacebo",
    "spir-v",
)

/** Coordinates loadfile callbacks with the Surface lifecycle without depending on Android/JNI. */
internal class SurfaceAwareMpvFileLoader(
    private val loadDirectly: (String) -> Unit,
    private val loadThroughView: (String) -> Unit,
) {
    private var surfaceAttached = false
    private val pendingAfterLoadfileActions = mutableListOf<() -> Unit>()

    val isSurfaceAttached: Boolean
        get() = surfaceAttached

    fun playFileWhenReady(uri: String, afterLoadfile: () -> Unit) {
        pendingAfterLoadfileActions.clear()
        if (surfaceAttached) {
            loadDirectly(uri)
            afterLoadfile()
        } else {
            pendingAfterLoadfileActions += afterLoadfile
            loadThroughView(uri)
        }
    }

    fun markSurfaceAttached(): Boolean {
        if (surfaceAttached) return false
        surfaceAttached = true
        return true
    }

    fun markSurfaceDetached(): Boolean {
        if (!surfaceAttached) return false
        surfaceAttached = false
        return true
    }

    fun flushPendingAfterLoadfileActions() {
        val actions = pendingAfterLoadfileActions.toList()
        pendingAfterLoadfileActions.clear()
        actions.forEach { it() }
    }
}
