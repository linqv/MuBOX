package org.mubox.gradle

import com.android.build.api.dsl.ApplicationExtension
import com.android.build.api.dsl.LibraryExtension
import org.gradle.api.Action
import org.gradle.api.GradleException
import org.gradle.api.JavaVersion
import org.gradle.api.Project
import org.gradle.api.artifacts.VersionCatalog
import org.gradle.api.artifacts.VersionCatalogsExtension
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinJvmCompilerOptions
import org.jetbrains.kotlin.gradle.tasks.KotlinJvmCompile

private val supportedTargetAbis = listOf("arm64-v8a", "x86_64")
private val targetAbiAliases = mapOf("arm64_v8a" to "arm64-v8a")

internal fun Project.configureMuboxAndroid(extension: LibraryExtension) {
    val libs = extensions.getByType(VersionCatalogsExtension::class.java).named("libs")
    val shared = createMuboxAndroidExtension(libs)
    extension.apply {
        compileSdk = libs.versionInt("compileSdk")
        defaultConfig {
            minSdk = shared.minSdk.get()
            ndk.abiFilters += shared.targetAbi.orNull?.let(::listOf) ?: shared.supportedAbis.get()
        }
        compileOptions {
            sourceCompatibility = JavaVersion.toVersion(libs.versionInt("jvm"))
            targetCompatibility = JavaVersion.toVersion(libs.versionInt("jvm"))
        }
        configureJvmTarget(libs.version("jvm"))
    }
}

internal fun Project.configureMuboxAndroid(extension: ApplicationExtension) {
    val libs = extensions.getByType(VersionCatalogsExtension::class.java).named("libs")
    val shared = createMuboxAndroidExtension(libs)
    extension.apply {
        compileSdk = libs.versionInt("compileSdk")
        defaultConfig {
            minSdk = shared.minSdk.get()
            ndk.abiFilters += shared.targetAbi.orNull?.let(::listOf) ?: shared.supportedAbis.get()
        }
        compileOptions {
            sourceCompatibility = JavaVersion.toVersion(libs.versionInt("jvm"))
            targetCompatibility = JavaVersion.toVersion(libs.versionInt("jvm"))
        }
        configureJvmTarget(libs.version("jvm"))
    }
}

private fun Project.configureJvmTarget(target: String) {
    tasks.withType(KotlinJvmCompile::class.java).configureEach(Action { task ->
        task.compilerOptions(Action<KotlinJvmCompilerOptions> { options ->
            options.jvmTarget.set(JvmTarget.fromTarget(target))
        })
    })
}

private fun Project.createMuboxAndroidExtension(libs: VersionCatalog): MuboxAndroidExtension {
    extensions.findByType(MuboxAndroidExtension::class.java)?.let { return it }

    val rawTargetAbi = providers.gradleProperty("targetAbi").orNull?.trim()?.takeIf(String::isNotBlank)
    val targetAbi = rawTargetAbi?.let { targetAbiAliases[it] ?: it }
    if (targetAbi != null && targetAbi !in supportedTargetAbis) {
        throw GradleException(
            "Unsupported targetAbi '$rawTargetAbi' (normalized to '$targetAbi'). " +
                "Supported values: ${supportedTargetAbis.joinToString()}",
        )
    }

    return extensions.create("muboxAndroid", MuboxAndroidExtension::class.java).apply {
        minSdk.set(libs.versionInt("minSdk"))
        supportedAbis.set(supportedTargetAbis)
        targetAbi?.let(this.targetAbi::set)
    }
}

private fun VersionCatalog.version(alias: String): String =
    findVersion(alias).get().requiredVersion

private fun VersionCatalog.versionInt(alias: String): Int = version(alias).toInt()
