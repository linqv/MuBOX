package com.example.comicdav

import android.content.pm.ActivityInfo
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Android16TargetSdkBehaviorTest {
    @Test
    fun buildTargetsAndroid16Api36() {
        val buildScript = appBuildGradleFile().readText()

        assertEquals(36, buildScript.gradleIntValue("compileAndroidSdk"))
        assertEquals(36, buildScript.gradleIntValue("targetAndroidSdk"))
        assertTrue(buildScript.contains("compileSdk = compileAndroidSdk"))
        assertTrue(buildScript.contains("targetSdk = targetAndroidSdk"))
    }

    @Test
    fun manifestDoesNotOptOutOfPredictiveBackOrEdgeToEdgeEnforcement() {
        val manifest = androidManifestFile().readText()
        val valuesXml = valuesXmlFiles().joinToString("\n") { it.readText() }

        assertFalse(manifest.contains("""android:enableOnBackInvokedCallback="false""""))
        assertFalse(valuesXml.contains("windowOptOutEdgeToEdgeEnforcement"))
    }

    @Test
    fun activitiesDoNotDeclareFixedManifestOrientation() {
        val manifest = androidManifestFile().readText()

        assertFalse(manifest.contains("android:screenOrientation"))
    }

    @Test
    fun mainAppUsesConfiguredRotationPolicy() {
        assertEquals(
            ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED,
            mainAppRequestedOrientation(screenRotationLockEnabled = false),
        )
        assertEquals(
            ActivityInfo.SCREEN_ORIENTATION_LOCKED,
            mainAppRequestedOrientation(screenRotationLockEnabled = true),
        )
    }

    @Test
    fun readerLandscapeAppliesOnlyWhileReaderIsOpen() {
        assertEquals(
            ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE,
            comicDavRequestedOrientation(
                screenRotationLockEnabled = false,
                isReaderOpen = true,
                readerLandscapeModeEnabled = true,
            ),
        )
        assertEquals(
            ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED,
            comicDavRequestedOrientation(
                screenRotationLockEnabled = false,
                isReaderOpen = false,
                readerLandscapeModeEnabled = true,
            ),
        )
        assertEquals(
            ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED,
            comicDavRequestedOrientation(
                screenRotationLockEnabled = false,
                isReaderOpen = true,
                readerLandscapeModeEnabled = false,
            ),
        )
    }

    @Test
    fun mainAppCanForcePortraitAfterTransientReaderOrPlayerLandscape() {
        assertEquals(
            ActivityInfo.SCREEN_ORIENTATION_PORTRAIT,
            comicDavRequestedOrientation(
                screenRotationLockEnabled = false,
                isReaderOpen = false,
                readerLandscapeModeEnabled = false,
                forceMainPortrait = true,
            ),
        )
        assertEquals(
            ActivityInfo.SCREEN_ORIENTATION_PORTRAIT,
            comicDavRequestedOrientation(
                screenRotationLockEnabled = true,
                isReaderOpen = false,
                readerLandscapeModeEnabled = false,
                forceMainPortrait = true,
            ),
        )
    }

    @Test
    fun readerCloseClearsLandscapeMode() {
        assertFalse(readerLandscapeModeAfterReaderClosed())
    }

    @Test
    fun networkSecurityConfigAllowsUserConfiguredHttpWebDavHosts() {
        val networkSecurityConfig = networkSecurityConfigFile().readText()

        assertTrue(
            networkSecurityConfig.contains("""<base-config cleartextTrafficPermitted="true""""),
        )
    }

    private fun appBuildGradleFile(): File =
        listOf(
            File("app/build.gradle.kts"),
            File("build.gradle.kts"),
        ).first { it.isFile && it.readText().contains("com.android.application") }

    private fun androidManifestFile(): File =
        listOf(
            File("app/src/main/AndroidManifest.xml"),
            File("src/main/AndroidManifest.xml"),
        ).first { it.isFile }

    private fun networkSecurityConfigFile(): File =
        listOf(
            File("app/src/main/res/xml/network_security_config.xml"),
            File("src/main/res/xml/network_security_config.xml"),
        ).first { it.isFile }

    private fun valuesXmlFiles(): List<File> =
        listOf(
            File("app/src/main/res/values"),
            File("src/main/res/values"),
        ).first { it.isDirectory }
            .walkTopDown()
            .filter { it.isFile && it.extension == "xml" }
            .toList()

    private fun String.gradleIntValue(name: String): Int {
        val match = Regex("""val\s+$name\s*=\s*(\d+)""").find(this)
        return checkNotNull(match) { "Missing Gradle integer value for $name" }
            .groupValues[1]
            .toInt()
    }
}
