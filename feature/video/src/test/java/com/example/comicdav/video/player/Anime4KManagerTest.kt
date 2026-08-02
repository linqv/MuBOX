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
    fun modeAFastBuildsDistinctFirstAndSecondUpscaleStages() {
        val shaderDir = temporaryFolder.newFolder("shaders")
        expectedAnime4KShaderAssetNames.forEach { File(shaderDir, it).writeText("// $it") }

        val chain = anime4kShaderChain(
            pipeline = Anime4KPipeline.MODE_A_FAST,
            shaderDir = shaderDir,
        )

        assertEquals(
            listOf(
                "Anime4K_Clamp_Highlights.glsl",
                "Anime4K_Restore_CNN_S.glsl",
                "Anime4K_Upscale_CNN_x2_M.glsl",
                "Anime4K_AutoDownscalePre_x2.glsl",
                "Anime4K_AutoDownscalePre_x4.glsl",
                "Anime4K_Upscale_CNN_x2_S.glsl",
            ).joinToString(":") { File(shaderDir, it).absolutePath },
            chain,
        )
    }

    @Test
    fun noPipelineLoadsTheSameShaderFileTwice() {
        Anime4KPipeline.entries.forEach { pipeline ->
            val shaderNames = anime4kShaderNames(pipeline)
            assertEquals(
                "Duplicate shader in $pipeline",
                shaderNames.distinct(),
                shaderNames,
            )
        }
    }

    @Test
    fun manualProfilesMapToExpectedPipelinesWhileAutoWaitsForVideoParams() {
        assertEquals(null, anime4kPipelineForProfile(Anime4KProfile.OFF))
        assertEquals(null, anime4kPipelineForProfile(Anime4KProfile.AUTO))
        assertEquals(
            Anime4KPipeline.MODE_A_FAST,
            anime4kPipelineForProfile(Anime4KProfile.EFFICIENCY),
        )
        assertEquals(
            Anime4KPipeline.MODE_C_A_HIGH,
            anime4kPipelineForProfile(Anime4KProfile.EXTREME),
        )
    }

    @Test
    fun missingShaderReturnsEmptyChainInsteadOfPartialChain() {
        val shaderDir = temporaryFolder.newFolder("shaders")
        expectedAnime4KShaderAssetNames
            .filterNot { it == "Anime4K_Upscale_CNN_x2_L.glsl" }
            .forEach { File(shaderDir, it).writeText("// $it") }

        assertEquals("", anime4kShaderChain(Anime4KPipeline.MODE_C_A_HIGH, shaderDir))
    }

    @Test
    fun autoSelectsBalancedModeAForStandardFrameRate1080p() {
        assertEquals(
            Anime4KPipeline.MODE_A_BALANCED,
            selectAnime4KPipeline(VideoParams(width = 1920, height = 1080, frameRate = 24.0)).pipeline,
        )
    }

    @Test
    fun autoSelectsFastModeAForHighFrameRate1080p() {
        assertEquals(
            Anime4KPipeline.MODE_A_FAST,
            selectAnime4KPipeline(VideoParams(width = 1920, height = 1080, frameRate = 60.0)).pipeline,
        )
    }

    @Test
    fun autoSelectsModeBFor720pAndModeCAForSd() {
        assertEquals(
            Anime4KPipeline.MODE_B_BALANCED,
            selectAnime4KPipeline(VideoParams(width = 1280, height = 720, frameRate = 24.0)).pipeline,
        )
        assertEquals(
            Anime4KPipeline.MODE_C_A_BALANCED,
            selectAnime4KPipeline(VideoParams(width = 854, height = 480, frameRate = 24.0)).pipeline,
        )
    }

    @Test
    fun autoWaitsForDimensionsAndSkipsHdrOrHighResolutionSources() {
        assertTrue(selectAnime4KPipeline(VideoParams()).waitingForVideoParams)

        val hdr = selectAnime4KPipeline(
            VideoParams(width = 1920, height = 1080, frameRate = 24.0, gamma = "pq"),
        )
        assertEquals(null, hdr.pipeline)
        assertTrue(hdr.statusMessage.orEmpty().contains("HDR"))

        val highResolution = selectAnime4KPipeline(
            VideoParams(width = 3840, height = 2160, frameRate = 24.0),
        )
        assertEquals(null, highResolution.pipeline)
        assertTrue(highResolution.statusMessage.orEmpty().contains("高分辨率"))
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
