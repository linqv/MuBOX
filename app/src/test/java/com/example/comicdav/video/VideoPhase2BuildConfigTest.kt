package com.example.comicdav.video

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoPhase2BuildConfigTest {
    @Test
    fun mpvAarIsBundledAndReferencedByGradle() {
        assertTrue(
            "Phase 2 requires the mpv Android AAR in app/libs",
            File("libs/mpv-android-lib-v0.0.1.aar").isFile ||
                File("app/libs/mpv-android-lib-v0.0.1.aar").isFile,
        )

        val buildScript = appBuildGradleFile().readText()
        assertTrue(
            buildScript.contains("""implementation(files("libs/mpv-android-lib-v0.0.1.aar"))"""),
        )
    }

    @Test
    fun nativeMpvLibrariesUseLegacyPackaging() {
        val buildScript = appBuildGradleFile().readText()

        assertTrue(buildScript.contains("packaging"))
        assertTrue(buildScript.contains("jniLibs"))
        assertTrue(buildScript.contains("useLegacyPackaging = true"))
    }

    @Test
    fun releaseRulesKeepMpvPublicApi() {
        val rules = proguardRulesFile().readText()

        assertTrue(
            rules.contains("-keep,allowoptimization class is.xyz.mpv.** { public protected *; }"),
        )
    }

    private fun appBuildGradleFile(): File =
        listOf(
            File("build.gradle.kts"),
            File("app/build.gradle.kts"),
        ).first { it.isFile && it.readText().contains("com.android.application") }

    private fun proguardRulesFile(): File =
        listOf(
            File("proguard-rules.pro"),
            File("app/proguard-rules.pro"),
        ).first { it.isFile }
}
