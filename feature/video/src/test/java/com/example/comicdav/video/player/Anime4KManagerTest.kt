package com.example.comicdav.video.player

import com.example.comicdav.core.model.settings.Anime4KProfile
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
    fun efficiencyBuildsSmallModelRestoreAndUpscaleChain() {
        val shaderDir = temporaryFolder.newFolder("shaders")
        expectedAnime4KShaderAssetNames.forEach { File(shaderDir, it).writeText("// $it") }

        val chain = anime4kShaderChain(
            profile = Anime4KProfile.EFFICIENCY,
            shaderDir = shaderDir,
        )

        assertEquals(
            listOf(
                "Anime4K_Clamp_Highlights.glsl",
                "Anime4K_Restore_CNN_S.glsl",
                "Anime4K_Upscale_CNN_x2_S.glsl",
                "Anime4K_AutoDownscalePre_x2.glsl",
                "Anime4K_Upscale_CNN_x2_S.glsl",
            ).joinToString(":") { File(shaderDir, it).absolutePath },
            chain,
        )
    }

    @Test
    fun offReturnsEmptyShaderChain() {
        val shaderDir = temporaryFolder.newFolder("shaders")
        expectedAnime4KShaderAssetNames.forEach { File(shaderDir, it).writeText("// $it") }

        assertEquals("", anime4kShaderChain(Anime4KProfile.OFF, shaderDir))
    }

    @Test
    fun missingShaderReturnsEmptyChainInsteadOfPartialChain() {
        val shaderDir = temporaryFolder.newFolder("shaders")
        expectedAnime4KShaderAssetNames
            .filterNot { it == "Anime4K_Upscale_CNN_x2_L.glsl" }
            .forEach { File(shaderDir, it).writeText("// $it") }

        assertEquals("", anime4kShaderChain(Anime4KProfile.EXTREME, shaderDir))
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
            manager.shaderChain(Anime4KProfile.EFFICIENCY),
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
            manager.shaderChain(Anime4KProfile.EFFICIENCY),
        )
        assertFalse("shaderChain should only inspect existing files", shaderDir.exists())
    }
}
