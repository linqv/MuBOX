package org.mubox.reader.video

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/** Guards the release inputs required by the embedded mpv runtime. */
class MpvPackagingContractTest {
    @Test
    fun mpvAarIsBundledAndReferencedByGradle() {
        val repositoryRoot = repositoryRoot()
        assertTrue(
            "The mpv Android AAR must exist in the vendored Maven repository",
            repositoryRoot.resolve(
                "third_party/android/is/xyz/mpv/mpv-android-lib/0.0.1/" +
                    "mpv-android-lib-0.0.1.aar",
            ).isFile,
        )

        val featureBuildScript = repositoryRoot.resolve("feature/video/build.gradle.kts").readText()
        assertTrue(
            featureBuildScript.contains(
                """implementation("is.xyz.mpv:mpv-android-lib:0.0.1")""",
            ),
        )
        assertTrue(
            "The app module must not own feature-specific AAR files",
            !appBuildGradleFile().readText().contains(".aar"),
        )
    }

    @Test
    fun nativeMpvLibrariesUseModernPackaging() {
        val buildScript = appBuildGradleFile().readText()

        assertTrue(buildScript.contains("packaging"))
        assertTrue(buildScript.contains("jniLibs"))
        assertTrue(buildScript.contains("useLegacyPackaging = false"))
    }

    @Test
    fun releaseRulesKeepMpvNativeAbiSurface() {
        val rules = proguardRulesFile().readText()

        assertTrue(
            rules.contains("-keep,allowoptimization class is.xyz.mpv.MPVLib"),
        )
        assertTrue(rules.contains("native <methods>;"))
        assertTrue(rules.contains("public static void eventProperty(...);"))
        assertTrue(rules.contains("public static void event(...);"))
        assertTrue(rules.contains("public static void logMessage(...);"))
        assertTrue(rules.contains("-keep,allowoptimization class is.xyz.mpv.MPVNode { *; }"))
        assertTrue(rules.contains("-keep,allowoptimization class is.xyz.mpv.MPVNode\$* { *; }"))
    }

    private fun appBuildGradleFile(): File =
        listOf(
            File("build.gradle.kts"),
            File("app/build.gradle.kts"),
        ).first { it.isFile && it.readText().contains("com.android.application") }

    private fun repositoryRoot(): File =
        generateSequence(File(".").canonicalFile, File::getParentFile)
            .first { it.resolve("settings.gradle.kts").isFile }

    private fun proguardRulesFile(): File =
        listOf(
            File("proguard-rules.pro"),
            File("app/proguard-rules.pro"),
        ).first { it.isFile }
}
