package com.example.comicdav.video.player

import android.content.Context
import com.example.comicdav.core.model.settings.Anime4KProfile
import com.example.comicdav.core.model.settings.GpuApiMode
import com.example.comicdav.core.model.settings.VideoOutputMode
import java.io.File

data class Anime4KStartupCompatibility(
    val effectiveVideoOutputMode: VideoOutputMode,
    val statusMessage: String? = null,
)

enum class Anime4KPipeline {
    MODE_A_FAST,
    MODE_A_BALANCED,
    MODE_B_FAST,
    MODE_B_BALANCED,
    MODE_C_A_FAST,
    MODE_C_A_BALANCED,
    MODE_C_A_HIGH,
}

internal data class Anime4KAutoSelection(
    val pipeline: Anime4KPipeline? = null,
    val statusMessage: String? = null,
    val waitingForVideoParams: Boolean = false,
)

@Suppress("UNUSED_PARAMETER")
internal fun anime4kStartupCompatibility(
    profile: Anime4KProfile,
    requestedVideoOutputMode: VideoOutputMode,
    gpuApiMode: GpuApiMode,
): Anime4KStartupCompatibility {
    // gpu-next implements mpv user shaders on both OpenGL and Vulkan. Do not downgrade the
    // renderer pre-emptively; device-specific shader failures should be diagnosed at runtime.
    return Anime4KStartupCompatibility(effectiveVideoOutputMode = requestedVideoOutputMode)
}

internal val expectedAnime4KShaderAssetNames = listOf(
    "Anime4K_Clamp_Highlights.glsl",
    "Anime4K_Restore_CNN_S.glsl",
    "Anime4K_Restore_CNN_M.glsl",
    "Anime4K_Restore_CNN_L.glsl",
    "Anime4K_Restore_CNN_Soft_S.glsl",
    "Anime4K_Restore_CNN_Soft_M.glsl",
    "Anime4K_Upscale_CNN_x2_S.glsl",
    "Anime4K_Upscale_CNN_x2_M.glsl",
    "Anime4K_Upscale_CNN_x2_L.glsl",
    "Anime4K_Upscale_Denoise_CNN_x2_S.glsl",
    "Anime4K_Upscale_Denoise_CNN_x2_M.glsl",
    "Anime4K_Upscale_Denoise_CNN_x2_L.glsl",
    "Anime4K_AutoDownscalePre_x2.glsl",
    "Anime4K_AutoDownscalePre_x4.glsl",
)

internal fun anime4kShaderChain(
    pipeline: Anime4KPipeline,
    shaderDir: File,
): String {
    val shaderFiles = anime4kShaderNames(pipeline).map { name -> File(shaderDir, name) }
    if (shaderFiles.any { file -> !file.isFile }) return ""
    return shaderFiles.joinToString(":") { file -> file.absolutePath }
}

internal fun anime4kPipelineForProfile(profile: Anime4KProfile): Anime4KPipeline? =
    when (profile) {
        Anime4KProfile.OFF,
        Anime4KProfile.AUTO,
        -> null
        Anime4KProfile.EFFICIENCY -> Anime4KPipeline.MODE_A_FAST
        Anime4KProfile.EXTREME -> Anime4KPipeline.MODE_C_A_HIGH
    }

internal fun selectAnime4KPipeline(
    videoParams: VideoParams,
    forceFast: Boolean = false,
): Anime4KAutoSelection {
    val width = videoParams.width
    val height = videoParams.height
    if (width == null || height == null || width <= 0 || height <= 0) {
        return Anime4KAutoSelection(waitingForVideoParams = true)
    }
    if (videoParams.gamma.isHdrTransfer()) {
        return Anime4KAutoSelection(statusMessage = "Anime4K 自动：HDR 视频已跳过")
    }

    // Resolution is a conservative proxy for the degradation classes described by Anime4K.
    // Manual profiles remain available because dimensions alone cannot identify blur or ringing.
    val sourceShortEdge = minOf(width, height)
    if (sourceShortEdge > AUTO_ANIME4K_MAX_SOURCE_HEIGHT) {
        return Anime4KAutoSelection(statusMessage = "Anime4K 自动：高分辨率视频无需增强")
    }

    val useFastPipeline =
        forceFast ||
            videoParams.frameRate == null ||
            videoParams.frameRate > AUTO_ANIME4K_BALANCED_MAX_FPS
    val pipeline = when {
        sourceShortEdge >= AUTO_ANIME4K_MODE_A_MIN_HEIGHT ->
            if (useFastPipeline) Anime4KPipeline.MODE_A_FAST else Anime4KPipeline.MODE_A_BALANCED
        sourceShortEdge >= AUTO_ANIME4K_MODE_B_MIN_HEIGHT ->
            if (useFastPipeline) Anime4KPipeline.MODE_B_FAST else Anime4KPipeline.MODE_B_BALANCED
        else ->
            if (useFastPipeline) Anime4KPipeline.MODE_C_A_FAST else Anime4KPipeline.MODE_C_A_BALANCED
    }
    return Anime4KAutoSelection(pipeline = pipeline)
}

internal fun anime4kShaderNames(pipeline: Anime4KPipeline): List<String> =
    when (pipeline) {
        Anime4KPipeline.MODE_A_FAST -> listOf(
            "Anime4K_Clamp_Highlights.glsl",
            "Anime4K_Restore_CNN_S.glsl",
            "Anime4K_Upscale_CNN_x2_M.glsl",
            "Anime4K_AutoDownscalePre_x2.glsl",
            "Anime4K_AutoDownscalePre_x4.glsl",
            "Anime4K_Upscale_CNN_x2_S.glsl",
        )
        Anime4KPipeline.MODE_A_BALANCED -> listOf(
            "Anime4K_Clamp_Highlights.glsl",
            "Anime4K_Restore_CNN_M.glsl",
            "Anime4K_Upscale_CNN_x2_M.glsl",
            "Anime4K_AutoDownscalePre_x2.glsl",
            "Anime4K_AutoDownscalePre_x4.glsl",
            "Anime4K_Upscale_CNN_x2_S.glsl",
        )
        Anime4KPipeline.MODE_B_FAST -> listOf(
            "Anime4K_Clamp_Highlights.glsl",
            "Anime4K_Restore_CNN_Soft_S.glsl",
            "Anime4K_Upscale_CNN_x2_M.glsl",
            "Anime4K_AutoDownscalePre_x2.glsl",
            "Anime4K_AutoDownscalePre_x4.glsl",
            "Anime4K_Upscale_CNN_x2_S.glsl",
        )
        Anime4KPipeline.MODE_B_BALANCED -> listOf(
            "Anime4K_Clamp_Highlights.glsl",
            "Anime4K_Restore_CNN_Soft_M.glsl",
            "Anime4K_Upscale_CNN_x2_M.glsl",
            "Anime4K_AutoDownscalePre_x2.glsl",
            "Anime4K_AutoDownscalePre_x4.glsl",
            "Anime4K_Upscale_CNN_x2_S.glsl",
        )
        Anime4KPipeline.MODE_C_A_FAST -> listOf(
            "Anime4K_Clamp_Highlights.glsl",
            "Anime4K_Upscale_Denoise_CNN_x2_S.glsl",
            "Anime4K_AutoDownscalePre_x2.glsl",
            "Anime4K_AutoDownscalePre_x4.glsl",
            "Anime4K_Restore_CNN_S.glsl",
            "Anime4K_Upscale_CNN_x2_S.glsl",
        )
        Anime4KPipeline.MODE_C_A_BALANCED -> listOf(
            "Anime4K_Clamp_Highlights.glsl",
            "Anime4K_Upscale_Denoise_CNN_x2_M.glsl",
            "Anime4K_AutoDownscalePre_x2.glsl",
            "Anime4K_AutoDownscalePre_x4.glsl",
            "Anime4K_Restore_CNN_S.glsl",
            "Anime4K_Upscale_CNN_x2_S.glsl",
        )
        Anime4KPipeline.MODE_C_A_HIGH -> listOf(
            "Anime4K_Clamp_Highlights.glsl",
            "Anime4K_Upscale_Denoise_CNN_x2_L.glsl",
            "Anime4K_AutoDownscalePre_x2.glsl",
            "Anime4K_AutoDownscalePre_x4.glsl",
            "Anime4K_Restore_CNN_L.glsl",
            "Anime4K_Upscale_CNN_x2_L.glsl",
        )
    }

internal fun staleAnime4KShaderFiles(
    shaderDir: File,
    expectedNames: List<String>,
): Sequence<File> {
    val expectedNameSet = expectedNames.toSet()
    return shaderDir
        .listFiles()
        .orEmpty()
        .asSequence()
        .filter { file ->
            file.isFile &&
                file.name.startsWith("Anime4K_") &&
                file.name.endsWith(".glsl") &&
                file.name !in expectedNameSet
        }
        .sortedBy { file -> file.name }
}

internal fun anime4kShaderAssetPath(assetName: String): String = "shaders/$assetName"

interface Anime4KShaderProvider {
    fun shaderChain(pipeline: Anime4KPipeline): String
}

object EmptyAnime4KShaderProvider : Anime4KShaderProvider {
    override fun shaderChain(pipeline: Anime4KPipeline): String = ""
}

class Anime4KManager(context: Context) : Anime4KShaderProvider {
    private val appContext = context.applicationContext
    private val shaderDir = File(appContext.filesDir, "shaders")
    private var initialized = false

    fun initialize(): Boolean {
        if (initialized) return true
        return runCatching {
            shaderDir.mkdirs()
            staleAnime4KShaderFiles(shaderDir, expectedAnime4KShaderAssetNames).forEach { file ->
                file.delete()
            }
            expectedAnime4KShaderAssetNames.forEach { assetName ->
                copyAssetIfChanged(assetName, File(shaderDir, assetName))
            }
            initialized = true
            true
        }.getOrDefault(false)
    }

    fun shaderChain(profile: Anime4KProfile): String =
        anime4kPipelineForProfile(profile)?.let(::shaderChain).orEmpty()

    override fun shaderChain(pipeline: Anime4KPipeline): String {
        return anime4kShaderChain(
            pipeline = pipeline,
            shaderDir = shaderDir,
        )
    }

    private fun copyAssetIfChanged(assetName: String, destination: File) {
        val assetBytes = appContext.assets.open(anime4kShaderAssetPath(assetName)).use { input ->
            input.readBytes()
        }
        if (destination.isFile && destination.readBytes().contentEquals(assetBytes)) return
        destination.parentFile?.mkdirs()
        destination.writeBytes(assetBytes)
    }
}

internal val Anime4KPipeline.isFastAutoPipeline: Boolean
    get() = when (this) {
        Anime4KPipeline.MODE_A_FAST,
        Anime4KPipeline.MODE_B_FAST,
        Anime4KPipeline.MODE_C_A_FAST,
        -> true
        else -> false
    }

internal val Anime4KPipeline.isBalancedAutoPipeline: Boolean
    get() = when (this) {
        Anime4KPipeline.MODE_A_BALANCED,
        Anime4KPipeline.MODE_B_BALANCED,
        Anime4KPipeline.MODE_C_A_BALANCED,
        -> true
        else -> false
    }

private fun String?.isHdrTransfer(): Boolean =
    this?.lowercase() in setOf("pq", "hlg")

private const val AUTO_ANIME4K_MAX_SOURCE_HEIGHT = 1_200
private const val AUTO_ANIME4K_MODE_A_MIN_HEIGHT = 900
private const val AUTO_ANIME4K_MODE_B_MIN_HEIGHT = 600
private const val AUTO_ANIME4K_BALANCED_MAX_FPS = 30.5
