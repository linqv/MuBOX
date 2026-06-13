package com.example.comicdav.video.player

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class Anime4KShaderAssetsTest {
    @Test
    fun bundledShaderAssetsMatchExpectedAnime4KShaders() {
        val shaderDir = shaderAssetsDir()

        assertTrue("Expected shader asset directory to exist: ${shaderDir.path}", shaderDir.isDirectory)
        assertEquals(
            expectedAnime4KShaderAssetNames.sorted(),
            shaderDir.listFiles()
                .orEmpty()
                .filter { it.isFile && it.name.startsWith("Anime4K_") && it.name.endsWith(".glsl") }
                .map { it.name }
                .sorted(),
        )
        expectedAnime4KShaderAssetNames.forEach { assetName ->
            val shaderFile = File(shaderDir, assetName)
            assertTrue("Expected non-empty shader asset: $assetName", shaderFile.length() > 0L)
        }
    }

    private fun shaderAssetsDir(): File =
        listOf(
            File("src/main/assets/shaders"),
            File("app/src/main/assets/shaders"),
        ).firstOrNull { it.exists() } ?: File("src/main/assets/shaders")
}
