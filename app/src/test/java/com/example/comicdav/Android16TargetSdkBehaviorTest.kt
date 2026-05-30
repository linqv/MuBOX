package com.example.comicdav

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Android16TargetSdkBehaviorTest {
    @Test
    fun buildTargetsAndroid16Api36() {
        val buildScript = appBuildGradleFile().readText()

        assertTrue(buildScript.contains("compileSdk = 36"))
        assertTrue(buildScript.contains("targetSdk = 36"))
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
}
