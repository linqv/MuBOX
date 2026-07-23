package com.example.comicdav.video.player

import android.content.Context
import com.example.comicdav.core.model.settings.Anime4KMode
import com.example.comicdav.core.model.settings.Anime4KQuality
import com.example.comicdav.core.model.settings.Anime4KSettings
import com.example.comicdav.core.model.settings.GpuApiMode
import com.example.comicdav.core.model.settings.VideoOutputMode
import java.io.File

data class Anime4KStartupCompatibility(
    val effectiveVideoOutputMode: VideoOutputMode,
    val statusMessage: String? = null,
)

@Suppress("UNUSED_PARAMETER")
internal fun anime4kStartupCompatibility(
    settings: Anime4KSettings,
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
    "Anime4K_Restore_CNN_Soft_L.glsl",
    "Anime4K_Upscale_CNN_x2_S.glsl",
    "Anime4K_Upscale_CNN_x2_M.glsl",
    "Anime4K_Upscale_CNN_x2_L.glsl",
    "Anime4K_Upscale_Denoise_CNN_x2_S.glsl",
    "Anime4K_Upscale_Denoise_CNN_x2_M.glsl",
    "Anime4K_Upscale_Denoise_CNN_x2_L.glsl",
    "Anime4K_AutoDownscalePre_x2.glsl",
)

internal fun anime4kShaderChain(
    enabled: Boolean,
    mode: Anime4KMode,
    quality: Anime4KQuality,
    shaderDir: File,
): String {
    if (!enabled || mode == Anime4KMode.OFF) return ""
    val shaderFiles = anime4kShaderNames(mode, quality).map { name -> File(shaderDir, name) }
    if (shaderFiles.any { file -> !file.isFile }) return ""
    return shaderFiles.joinToString(":") { file -> file.absolutePath }
}

internal fun anime4kShaderNames(mode: Anime4KMode, quality: Anime4KQuality): List<String> {
    if (mode == Anime4KMode.OFF) return emptyList()

    val suffix = quality.shaderSuffix
    val restore = "Anime4K_Restore_CNN_$suffix.glsl"
    val restoreSoft = "Anime4K_Restore_CNN_Soft_$suffix.glsl"
    val upscale = "Anime4K_Upscale_CNN_x2_$suffix.glsl"
    val upscaleDenoise = "Anime4K_Upscale_Denoise_CNN_x2_$suffix.glsl"
    val autoDownscalePre = "Anime4K_AutoDownscalePre_x2.glsl"

    val modeShaders = when (mode) {
        Anime4KMode.OFF -> emptyList()
        Anime4KMode.A -> listOf(restore, upscale, autoDownscalePre, upscale)
        Anime4KMode.B -> listOf(restoreSoft, upscale, autoDownscalePre, upscale)
        Anime4KMode.C -> listOf(upscaleDenoise, autoDownscalePre, upscale)
        Anime4KMode.A_PLUS -> listOf(restore, upscale, autoDownscalePre, restore, upscale)
        Anime4KMode.B_PLUS -> listOf(restoreSoft, upscale, autoDownscalePre, restoreSoft, upscale)
        Anime4KMode.C_PLUS -> listOf(upscaleDenoise, autoDownscalePre, restore, upscale)
    }

    return listOf("Anime4K_Clamp_Highlights.glsl") + modeShaders
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

private val Anime4KQuality.shaderSuffix: String
    get() = when (this) {
        Anime4KQuality.FAST -> "S"
        Anime4KQuality.BALANCED -> "M"
        Anime4KQuality.HIGH -> "L"
    }

interface Anime4KShaderProvider {
    fun shaderChain(settings: Anime4KSettings): String
}

object EmptyAnime4KShaderProvider : Anime4KShaderProvider {
    override fun shaderChain(settings: Anime4KSettings): String = ""
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

    override fun shaderChain(settings: Anime4KSettings): String {
        if (!settings.enabled || settings.mode == Anime4KMode.OFF) return ""
        return anime4kShaderChain(
            enabled = settings.enabled,
            mode = settings.mode,
            quality = settings.quality,
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
