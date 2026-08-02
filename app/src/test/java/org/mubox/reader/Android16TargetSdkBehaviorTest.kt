package org.mubox.reader

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
        val versionCatalog = versionCatalogFile().readText()
        val androidConvention = androidConventionFile().readText()

        assertEquals(36, versionCatalog.versionCatalogInt("compileSdk"))
        assertEquals(36, versionCatalog.versionCatalogInt("targetSdk"))
        assertTrue(androidConvention.contains("compileSdk = libs.versionInt(\"compileSdk\")"))
        assertTrue(buildScript.contains("targetSdk = libs.versions.targetSdk.get().toInt()"))
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
            ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE,
            muBoxRequestedOrientation(
                screenRotationLockEnabled = false,
                isReaderOpen = true,
                readerLandscapeModeEnabled = true,
            ),
        )
        assertEquals(
            ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED,
            muBoxRequestedOrientation(
                screenRotationLockEnabled = false,
                isReaderOpen = false,
                readerLandscapeModeEnabled = true,
            ),
        )
        assertEquals(
            ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED,
            muBoxRequestedOrientation(
                screenRotationLockEnabled = false,
                isReaderOpen = true,
                readerLandscapeModeEnabled = false,
            ),
        )
    }

    @Test
    fun readerLandscapeCanLockSensorSwitching() {
        assertEquals(
            ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE,
            muBoxRequestedOrientation(
                screenRotationLockEnabled = false,
                isReaderOpen = true,
                readerLandscapeModeEnabled = true,
                readerLandscapeOrientationLocked = true,
            ),
        )
    }

    @Test
    fun mainAppCanForcePortraitAfterTransientReaderOrPlayerLandscape() {
        assertEquals(
            ActivityInfo.SCREEN_ORIENTATION_PORTRAIT,
            muBoxRequestedOrientation(
                screenRotationLockEnabled = false,
                isReaderOpen = false,
                readerLandscapeModeEnabled = false,
                forceMainPortrait = true,
            ),
        )
        assertEquals(
            ActivityInfo.SCREEN_ORIENTATION_PORTRAIT,
            muBoxRequestedOrientation(
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

    private fun versionCatalogFile(): File =
        repositoryFile("gradle/libs.versions.toml")

    private fun androidConventionFile(): File =
        repositoryFile("build-logic/src/main/kotlin/org/mubox/gradle/MuboxAndroid.kt")

    private fun repositoryFile(path: String): File =
        generateSequence(File(".").canonicalFile, File::getParentFile)
            .map { it.resolve(path) }
            .first { it.isFile }

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

    private fun String.versionCatalogInt(name: String): Int {
        val match = Regex("""(?m)^$name\s*=\s*"(\d+)"\s*$""").find(this)
        return checkNotNull(match) { "Missing version catalog integer for $name" }
            .groupValues[1]
            .toInt()
    }
}
