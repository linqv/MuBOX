import org.gradle.api.GradleException
import org.gradle.api.tasks.Exec
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

data class RustAndroidTarget(
    val abi: String,
    val triple: String,
    val linkerName: String,
    val linkerEnv: String,
)

val generatedRustJniLibs = layout.buildDirectory.dir("generated/rustJniLibs/debug")

android {
    namespace = "com.example.comicdav"
    compileSdk = 35
    buildToolsVersion = "35.0.1"

    defaultConfig {
        applicationId = "com.example.comicdav"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        ndk {
            abiFilters += listOf("arm64-v8a", "x86_64")
        }
    }

    buildFeatures {
        compose = true
    }

    sourceSets {
        getByName("main") {
            jniLibs.srcDir(generatedRustJniLibs)
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }
}

tasks.register<Exec>("buildRustDebug") {
    workingDir = file("../comic-core")
    commandLine("cargo", "build")
}

tasks.named("preBuild") {
    dependsOn("buildRustDebug")
}

tasks.register("buildRustAndroidDebug") {
    val outputRoot = generatedRustJniLibs
    outputs.dir(outputRoot)

    doLast {
        val sdkDir = androidSdkDir()
        val ndkRoot = latestNdkDir(sdkDir)
        val toolchainBin = ndkRoot.resolve("toolchains/llvm/prebuilt/linux-x86_64/bin")
        if (!toolchainBin.isDirectory) {
            throw GradleException("Android NDK LLVM toolchain not found at $toolchainBin")
        }

        val targets = listOf(
            RustAndroidTarget(
                abi = "arm64-v8a",
                triple = "aarch64-linux-android",
                linkerName = "aarch64-linux-android35-clang",
                linkerEnv = "CARGO_TARGET_AARCH64_LINUX_ANDROID_LINKER",
            ),
            RustAndroidTarget(
                abi = "x86_64",
                triple = "x86_64-linux-android",
                linkerName = "x86_64-linux-android35-clang",
                linkerEnv = "CARGO_TARGET_X86_64_LINUX_ANDROID_LINKER",
            ),
        )

        val outputDir = outputRoot.get().asFile
        project.delete(outputDir)
        targets.forEach { target ->
            val linker = toolchainBin.resolve(target.linkerName)
            if (!linker.isFile) {
                throw GradleException("Android linker for ${target.abi} not found at $linker")
            }

            providers.exec {
                workingDir = file("../comic-core")
                environment(target.linkerEnv, linker.absolutePath)
                commandLine("cargo", "build", "--target", target.triple)
            }.result.get()

            val sourceLibrary = file("../comic-core/target/${target.triple}/debug/libcomic_core.so")
            if (!sourceLibrary.isFile) {
                throw GradleException("Rust output not found at $sourceLibrary")
            }
            val abiDir = outputDir.resolve(target.abi)
            abiDir.mkdirs()
            sourceLibrary.copyTo(abiDir.resolve("libcomic_core.so"), overwrite = true)
        }
    }
}

tasks.matching { it.name == "mergeDebugJniLibFolders" }.configureEach {
    dependsOn("buildRustAndroidDebug")
}

fun androidSdkDir(): File {
    val localPropertiesFile = rootProject.file("local.properties")
    if (localPropertiesFile.isFile) {
        val properties = Properties()
        localPropertiesFile.inputStream().use(properties::load)
        properties.getProperty("sdk.dir")?.let { return file(it) }
    }
    System.getenv("ANDROID_HOME")?.let { return file(it) }
    System.getenv("ANDROID_SDK_ROOT")?.let { return file(it) }
    throw GradleException("Android SDK directory not configured")
}

fun latestNdkDir(sdkDir: File): File {
    val ndkSideBySide = sdkDir.resolve("ndk")
    val latestSideBySide = ndkSideBySide
        .listFiles { file -> file.isDirectory }
        ?.maxByOrNull { it.name }
    if (latestSideBySide != null) return latestSideBySide

    val legacyNdk = sdkDir.resolve("ndk-bundle")
    if (legacyNdk.isDirectory) return legacyNdk

    throw GradleException("Android NDK not found under $sdkDir. Install an NDK before assembling the APK.")
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    androidTestImplementation(composeBom)

    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("io.coil-kt.coil3:coil-compose:3.0.4")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    debugImplementation("androidx.compose.ui:ui-tooling")

    testImplementation("junit:junit:4.13.2")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
}
