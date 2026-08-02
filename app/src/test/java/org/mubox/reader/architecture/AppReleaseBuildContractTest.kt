package org.mubox.reader.architecture

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class AppReleaseBuildContractTest {
    @Test
    fun releaseBuildUsesOptimizingDefaultRules() {
        val buildScript = appBuildGradleFile().readText()

        assertTrue(buildScript.contains("getDefaultProguardFile(\"proguard-android-optimize.txt\")"))
    }

    private fun appBuildGradleFile(): File =
        listOf(
            File("build.gradle.kts"),
            File("app/build.gradle.kts"),
        ).first { it.isFile && it.readText().contains("com.android.application") }
}
