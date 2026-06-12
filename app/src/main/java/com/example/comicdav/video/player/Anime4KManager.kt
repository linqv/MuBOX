package com.example.comicdav.video.player

import android.content.Context
import java.io.File

enum class Anime4KMode(val label: String) {
    OFF("关闭"),
    A("A"),
    B("B"),
    C("C"),
    A_PLUS("A+"),
    B_PLUS("B+"),
    C_PLUS("C+"),
}

enum class Anime4KQuality(val label: String, val suffix: String) {
    FAST("Fast", "S"),
    BALANCED("Balanced", "M"),
    HIGH("High", "L"),
}

data class Anime4KSettings(
    val enabled: Boolean = false,
    val mode: Anime4KMode = Anime4KMode.A,
    val quality: Anime4KQuality = Anime4KQuality.FAST,
)

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

    val suffix = quality.suffix
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

class Anime4KManager(context: Context) {
    private val appContext = context.applicationContext
    private val shaderDir = File(appContext.filesDir, "shaders")

    fun initialize() {
        shaderDir.mkdirs()
        staleAnime4KShaderFiles(shaderDir, expectedAnime4KShaderAssetNames).forEach { file ->
            file.delete()
        }
        expectedAnime4KShaderAssetNames.forEach { assetName ->
            copyAssetIfChanged(assetName, File(shaderDir, assetName))
        }
    }

    fun shaderChain(settings: Anime4KSettings): String =
        anime4kShaderChain(
            enabled = settings.enabled,
            mode = settings.mode,
            quality = settings.quality,
            shaderDir = shaderDir,
        )

    private fun copyAssetIfChanged(assetName: String, destination: File) {
        val assetBytes = appContext.assets.open(assetName).use { input -> input.readBytes() }
        if (destination.isFile && destination.readBytes().contentEquals(assetBytes)) return
        destination.parentFile?.mkdirs()
        destination.writeBytes(assetBytes)
    }
}
