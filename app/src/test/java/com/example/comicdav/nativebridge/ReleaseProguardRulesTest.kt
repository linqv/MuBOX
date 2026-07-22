package com.example.comicdav.nativebridge

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class ReleaseProguardRulesTest {
    @Test
    fun releaseRulesKeepJniLookupClassesAndNativeMethods() {
        val rules = proguardRulesFile().readText()

        assertTrue(rules.contains("-keep class com.example.comicdav.nativebridge.ComicNative { *; }"))
        assertTrue(rules.contains("-keep class com.example.comicdav.nativebridge.ComicNativeFacade { *; }"))
        assertTrue(rules.contains("-keep class com.example.comicdav.nativebridge.RangeProviderRegistry { *; }"))
        assertTrue(rules.contains("-keepclassmembers class com.example.comicdav.nativebridge.**"))
        assertTrue(rules.contains("-keepclasseswithmembernames class *"))
        assertTrue(rules.contains("native <methods>;"))
    }

    private fun proguardRulesFile(): File =
        listOf(
            File("proguard-rules.pro"),
            File("app/proguard-rules.pro"),
        ).first { it.isFile }
}
