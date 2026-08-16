package org.mubox.reader.nativebridge

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RustAndroidBuildInputsTest {
    @Test
    fun nativeBridgeOwnsCacheableRustAndroidBuildTasks() {
        val nativeBridgeBuildScript = moduleBuildFile("nativebridge").readText()
        val videoBuildScript = moduleBuildFile("feature/video").readText()
        val rustPlugin = repositoryRoot
            .resolve("build-logic/src/main/kotlin/org/mubox/gradle/MuboxRustAndroidPlugin.kt")
            .readText()
        val appBuildScript = moduleBuildFile("app").readText()

        assertTrue(rustPlugin.contains("src/**/*.rs"))
        assertTrue(rustPlugin.contains("Cargo.toml"))
        assertTrue(rustPlugin.contains("Cargo.lock"))
        assertTrue(rustPlugin.contains("@get:PathSensitive(PathSensitivity.RELATIVE)"))
        assertTrue(rustPlugin.contains("@CacheableTask"))
        assertTrue(rustPlugin.contains("CompileRustAndroidLibrary"))
        assertTrue(nativeBridgeBuildScript.contains("id(\"mubox.rust.android\")"))
        assertTrue(nativeBridgeBuildScript.contains("libraryName.set(\"comic_core\")"))
        assertTrue(videoBuildScript.contains("id(\"mubox.rust.android\")"))
        assertTrue(videoBuildScript.contains("libraryName.set(\"media_proxy_core\")"))
        assertFalse(appBuildScript.contains("CompileRustAndroidLibrary"))
        assertFalse(appBuildScript.contains("generatedRustJniLibs"))
    }

    @Test
    fun androidConventionNormalizesTargetAbiBeforeNativeBridgeFiltersRustTargets() {
        val buildScript = moduleBuildFile("nativebridge").readText()
        val rustPlugin = repositoryRoot
            .resolve("build-logic/src/main/kotlin/org/mubox/gradle/MuboxRustAndroidPlugin.kt")
            .readText()
        val androidConvention = repositoryRoot
            .resolve("build-logic/src/main/kotlin/org/mubox/gradle/MuboxAndroid.kt")
            .readText()

        assertTrue(androidConvention.contains("targetAbiAliases"))
        assertTrue(androidConvention.contains("\"arm64_v8a\" to \"arm64-v8a\""))
        assertTrue(androidConvention.contains("rawTargetAbi"))
        assertTrue(androidConvention.contains("Unsupported targetAbi"))
        assertTrue(buildScript.contains("id(\"mubox.rust.android\")"))
        assertTrue(rustPlugin.contains("extensions.getByType(MuboxAndroidExtension::class.java)"))
        assertTrue(rustPlugin.contains("targetAbi = shared.targetAbi.orNull"))
    }

    private fun moduleBuildFile(module: String): File = File(repositoryRoot, "$module/build.gradle.kts")

    private val repositoryRoot: File by lazy {
        generateSequence(File(".").absoluteFile.normalize()) { directory -> directory.parentFile }
            .firstOrNull { directory -> File(directory, "settings.gradle.kts").isFile }
            ?: error("Could not locate repository root from ${File(".").absolutePath}")
    }
}
