package com.example.comicdav.nativebridge

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class RustAndroidBuildInputsTest {
    @Test
    fun rustAndroidBuildTasksTrackRustSourcesAndCargoMetadata() {
        val buildScript = appBuildGradleFile().readText()

        assertTrue(buildScript.contains("src/**/*.rs"))
        assertTrue(buildScript.contains("Cargo.toml"))
        assertTrue(buildScript.contains("Cargo.lock"))
        assertTrue(buildScript.contains("@get:PathSensitive(PathSensitivity.RELATIVE)"))
        assertTrue(buildScript.contains("@CacheableTask"))
        assertTrue(buildScript.contains("CompileRustAndroidLibrary"))
    }

    @Test
    fun targetAbiPropertyNormalizesCommonArm64AliasBeforeFilteringNativeLibs() {
        val buildScript = appBuildGradleFile().readText()

        assertTrue(buildScript.contains("normalizeTargetAbi"))
        assertTrue(buildScript.contains("\"arm64_v8a\" to \"arm64-v8a\""))
        assertTrue(buildScript.contains("rawTargetAbi"))
        assertTrue(buildScript.contains("supportedTargetAbis"))
    }

    private fun appBuildGradleFile(): File =
        listOf(
            File("build.gradle.kts"),
            File("app/build.gradle.kts"),
        ).first { it.isFile && it.readText().contains("registerRustAndroidVariant") }
}
