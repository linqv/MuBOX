package com.example.comicdav.video.player

import com.example.comicdav.core.model.settings.Anime4KMode
import com.example.comicdav.core.model.settings.Anime4KQuality
import com.example.comicdav.core.model.settings.Anime4KSettings
import android.content.Context
import android.content.ContextWrapper
import android.content.res.AssetManager
import androidx.test.core.app.ApplicationProvider
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class Anime4KManagerTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun modeABalancedBuildsExpectedShaderChain() {
        val shaderDir = temporaryFolder.newFolder("shaders")
        expectedAnime4KShaderAssetNames.forEach { File(shaderDir, it).writeText("// $it") }

        val chain = anime4kShaderChain(
            enabled = true,
            mode = Anime4KMode.A,
            quality = Anime4KQuality.BALANCED,
            shaderDir = shaderDir,
        )

        assertEquals(
            listOf(
                "Anime4K_Clamp_Highlights.glsl",
                "Anime4K_Restore_CNN_M.glsl",
                "Anime4K_Upscale_CNN_x2_M.glsl",
                "Anime4K_AutoDownscalePre_x2.glsl",
                "Anime4K_Upscale_CNN_x2_M.glsl",
            ).joinToString(":") { File(shaderDir, it).absolutePath },
            chain,
        )
    }

    @Test
    fun disabledOrOffModeReturnsEmptyShaderChain() {
        val shaderDir = temporaryFolder.newFolder("shaders")
        expectedAnime4KShaderAssetNames.forEach { File(shaderDir, it).writeText("// $it") }

        assertEquals("", anime4kShaderChain(false, Anime4KMode.A, Anime4KQuality.FAST, shaderDir))
        assertEquals("", anime4kShaderChain(true, Anime4KMode.OFF, Anime4KQuality.FAST, shaderDir))
    }

    @Test
    fun missingShaderReturnsEmptyChainInsteadOfPartialChain() {
        val shaderDir = temporaryFolder.newFolder("shaders")
        expectedAnime4KShaderAssetNames
            .filterNot { it == "Anime4K_Upscale_CNN_x2_L.glsl" }
            .forEach { File(shaderDir, it).writeText("// $it") }

        assertEquals("", anime4kShaderChain(true, Anime4KMode.A, Anime4KQuality.HIGH, shaderDir))
    }

    @Test
    fun staleAnime4KFilesAreIdentifiedForCleanup() {
        val shaderDir = temporaryFolder.newFolder("shaders")
        val stale = File(shaderDir, "Anime4K_Old_Filter.glsl").apply { writeText("// stale") }
        val unrelated = File(shaderDir, "custom_shader.glsl").apply { writeText("// keep") }

        assertEquals(listOf(stale), staleAnime4KShaderFiles(shaderDir, expectedAnime4KShaderAssetNames).toList())
        assertTrue(unrelated.exists())
    }

    @Test
    fun shaderAssetsAreOpenedFromPackagedShaderDirectory() {
        assertEquals(
            "shaders/Anime4K_Clamp_Highlights.glsl",
            anime4kShaderAssetPath("Anime4K_Clamp_Highlights.glsl"),
        )
    }

    @Test
    fun managerReturnsEmptyShaderChainWhenAssetsCannotInitialize() {
        val context = object : ContextWrapper(ApplicationProvider.getApplicationContext<Context>()) {
            override fun getApplicationContext(): Context = this
            override fun getAssets(): AssetManager = error("assets unavailable")
        }
        val manager = Anime4KManager(context)

        assertFalse(manager.initialize())
        assertEquals(
            "",
            manager.shaderChain(
                Anime4KSettings(
                    enabled = true,
                    mode = Anime4KMode.A,
                    quality = Anime4KQuality.FAST,
                ),
            ),
        )
    }

    @Test
    fun shaderChainDoesNotInitializeShaderAssetsAtRuntime() {
        val filesDir = temporaryFolder.newFolder("runtime-files")
        val context = object : ContextWrapper(ApplicationProvider.getApplicationContext<Context>()) {
            override fun getApplicationContext(): Context = this
            override fun getFilesDir(): File = filesDir
        }
        val shaderDir = File(filesDir, "shaders")
        val manager = Anime4KManager(context)

        assertEquals(
            "",
            manager.shaderChain(
                Anime4KSettings(
                    enabled = true,
                    mode = Anime4KMode.A,
                    quality = Anime4KQuality.FAST,
                ),
            ),
        )
        assertFalse("shaderChain should only inspect existing files", shaderDir.exists())
    }
}
