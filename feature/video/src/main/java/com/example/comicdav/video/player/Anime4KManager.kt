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
    "Anime4K_Restore_CNN_L.glsl",
    "Anime4K_Upscale_CNN_x2_S.glsl",
    "Anime4K_Upscale_CNN_x2_L.glsl",
    "Anime4K_Upscale_Denoise_CNN_x2_L.glsl",
    "Anime4K_AutoDownscalePre_x2.glsl",
)

internal fun anime4kShaderChain(
    profile: Anime4KProfile,
    shaderDir: File,
): String {
    val shaderFiles = anime4kShaderNames(profile).map { name -> File(shaderDir, name) }
    if (shaderFiles.isEmpty()) return ""
    if (shaderFiles.any { file -> !file.isFile }) return ""
    return shaderFiles.joinToString(":") { file -> file.absolutePath }
}

internal fun anime4kShaderNames(profile: Anime4KProfile): List<String> =
    when (profile) {
        Anime4KProfile.OFF -> emptyList()
        Anime4KProfile.EFFICIENCY -> listOf(
            "Anime4K_Clamp_Highlights.glsl",
            "Anime4K_Restore_CNN_S.glsl",
            "Anime4K_Upscale_CNN_x2_S.glsl",
            "Anime4K_AutoDownscalePre_x2.glsl",
            "Anime4K_Upscale_CNN_x2_S.glsl",
        )
        Anime4KProfile.EXTREME -> listOf(
            "Anime4K_Clamp_Highlights.glsl",
            "Anime4K_Upscale_Denoise_CNN_x2_L.glsl",
            "Anime4K_AutoDownscalePre_x2.glsl",
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
    fun shaderChain(profile: Anime4KProfile): String
}

object EmptyAnime4KShaderProvider : Anime4KShaderProvider {
    override fun shaderChain(profile: Anime4KProfile): String = ""
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

    override fun shaderChain(profile: Anime4KProfile): String {
        return anime4kShaderChain(
            profile = profile,
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
