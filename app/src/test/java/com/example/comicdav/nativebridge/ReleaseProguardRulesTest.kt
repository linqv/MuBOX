package com.example.comicdav.nativebridge

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class ReleaseProguardRulesTest {
    @Test
    fun releaseRulesKeepJniLookupClassesAndNativeMethods() {
        val rules = proguardRulesFile().readText()

        assertTrue(
            rules.contains(
                "-keep,allowoptimization class " +
                    "com.example.comicdav.nativebridge.ComicNative",
            ),
        )
        assertTrue(rules.contains("native <methods>;"))
        assertTrue(
            rules.contains("-keepnames class com.example.comicdav.nativebridge.RangeProviderRegistry"),
        )
        assertTrue(
            rules.contains(
                "-keepclassmembers,allowoptimization class " +
                    "com.example.comicdav.nativebridge.RangeProviderRegistry",
            ),
        )
        assertTrue(rules.contains("public static byte[] readRange(long, long, long);"))
        assertTrue(rules.contains("public static byte[] readCachedRange(long, long, long);"))
    }

    @Test
    fun releaseBuildUsesDefaultRulesAlongsideExplicitDynamicRegistrationRule() {
        val buildScript = appBuildGradleFile().readText()

        assertTrue(buildScript.contains("getDefaultProguardFile(\"proguard-android-optimize.txt\")"))
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
