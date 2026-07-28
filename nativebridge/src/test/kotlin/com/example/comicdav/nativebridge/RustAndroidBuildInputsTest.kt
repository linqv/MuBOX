package com.example.comicdav.nativebridge

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RustAndroidBuildInputsTest {
    @Test
    fun nativeBridgeOwnsCacheableRustAndroidBuildTasks() {
        val nativeBridgeBuildScript = moduleBuildFile("nativebridge").readText()
        val appBuildScript = moduleBuildFile("app").readText()

        assertTrue(nativeBridgeBuildScript.contains("src/**/*.rs"))
        assertTrue(nativeBridgeBuildScript.contains("Cargo.toml"))
        assertTrue(nativeBridgeBuildScript.contains("Cargo.lock"))
        assertTrue(nativeBridgeBuildScript.contains("@get:PathSensitive(PathSensitivity.RELATIVE)"))
        assertTrue(nativeBridgeBuildScript.contains("@CacheableTask"))
        assertTrue(nativeBridgeBuildScript.contains("CompileRustAndroidLibrary"))
        assertTrue(nativeBridgeBuildScript.contains("registerRustAndroidVariant"))
        assertFalse(appBuildScript.contains("CompileRustAndroidLibrary"))
        assertFalse(appBuildScript.contains("registerRustAndroidVariant"))
        assertFalse(appBuildScript.contains("generatedRustJniLibs"))
    }

    @Test
    fun targetAbiPropertyNormalizesCommonArm64AliasBeforeFilteringNativeLibs() {
        val buildScript = moduleBuildFile("nativebridge").readText()

        assertTrue(buildScript.contains("normalizeTargetAbi"))
        assertTrue(buildScript.contains("\"arm64_v8a\" to \"arm64-v8a\""))
        assertTrue(buildScript.contains("rawTargetAbi"))
        assertTrue(buildScript.contains("supportedTargetAbis"))
    }

    private fun moduleBuildFile(module: String): File = File(repositoryRoot, "$module/build.gradle.kts")

    private val repositoryRoot: File by lazy {
        generateSequence(File(".").absoluteFile.normalize()) { directory -> directory.parentFile }
            .firstOrNull { directory -> File(directory, "settings.gradle.kts").isFile }
            ?: error("Could not locate repository root from ${File(".").absolutePath}")
    }
}
