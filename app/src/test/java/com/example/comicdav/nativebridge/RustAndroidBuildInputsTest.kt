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
        assertTrue(buildScript.contains("withPathSensitivity(PathSensitivity.RELATIVE)"))
    }

    private fun appBuildGradleFile(): File =
        listOf(
            File("build.gradle.kts"),
            File("app/build.gradle.kts"),
        ).first { it.isFile && it.readText().contains("buildRustAndroidVariant") }
}
