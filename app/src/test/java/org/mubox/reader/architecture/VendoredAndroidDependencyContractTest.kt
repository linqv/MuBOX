package org.mubox.reader.architecture

import java.io.File
import java.security.MessageDigest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VendoredAndroidDependencyContractTest {
    private val root = repositoryRoot()

    @Test
    fun featureModulesOwnTheirVendoredAndroidDependencies() {
        val appBuild = root.resolve("app/build.gradle.kts").readText()
        val readerBuild = root.resolve("feature/reader/build.gradle.kts").readText()
        val videoBuild = root.resolve("feature/video/build.gradle.kts").readText()
        val versionCatalog = root.resolve("gradle/libs.versions.toml").readText()

        assertFalse("The app module must not reference feature AAR files.", appBuild.contains(".aar"))
        assertTrue(readerBuild.contains("implementation(libs.mupdf)"))
        assertTrue(videoBuild.contains("implementation(libs.mpv)"))
        assertTrue(versionCatalog.contains("""mupdf = "1.27.1""""))
        assertTrue(versionCatalog.contains("""mupdf = { module = "com.artifex.mupdf:fitz", version.ref = "mupdf" }"""))
        assertTrue(versionCatalog.contains("""mpv = "0.0.1""""))
        assertTrue(versionCatalog.contains("""mpv = { module = "is.xyz.mpv:mpv-android-lib", version.ref = "mpv" }"""))
        assertFalse(readerBuild.contains("app/libs"))
        assertFalse(videoBuild.contains("app/libs"))
    }

    @Test
    fun vendoredArtifactsHaveStableCoordinatesAndChecksums() {
        val fitz = root.resolve(
            "third_party/android/com/artifex/mupdf/fitz/1.27.1/fitz-1.27.1.aar",
        )
        val mpv = root.resolve(
            "third_party/android/is/xyz/mpv/mpv-android-lib/0.0.1/mpv-android-lib-0.0.1.aar",
        )

        assertTrue(fitz.isFile)
        assertTrue(mpv.isFile)
        assertEquals(
            "005b747a7b3e3a22e6bb6f0f4a1e1eb1bfd3493793412d5a7ebfe654c6626229",
            fitz.sha256(),
        )
        assertEquals(
            "c8e6a563ffe104fa73ced45b786e616247450bf25b6537d245d4f83d2842e304",
            mpv.sha256(),
        )
    }

    @Test
    fun vendoredRepositoryIsRegisteredBeforeRemoteRepositories() {
        val settings = root.resolve("settings.gradle.kts").readText()
        val localRepository = settings.indexOf("""name = "vendoredAndroid"""")
        val firstRemoteRepository = settings.indexOf(
            """maven("https://mirrors.cloud.tencent.com/nexus/repository/maven-public/")""",
            startIndex = settings.indexOf("dependencyResolutionManagement"),
        )

        assertTrue(localRepository >= 0)
        assertTrue(firstRemoteRepository > localRepository)
    }

    private fun File.sha256(): String {
        val digest = MessageDigest.getInstance("SHA-256")
        inputStream().buffered().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString(separator = "") { byte -> "%02x".format(byte) }
    }

    private fun repositoryRoot(): File =
        generateSequence(File(".").canonicalFile, File::getParentFile)
            .first { it.resolve("settings.gradle.kts").isFile }
}
