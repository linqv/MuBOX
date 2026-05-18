import org.gradle.api.GradleException
import org.gradle.api.tasks.Exec
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.kapt")
    id("org.jetbrains.kotlin.plugin.compose")
}

data class RustAndroidTarget(
    val abi: String,
    val triple: String,
    val linkerName: String,
    val linkerEnv: String,
)

val generatedRustJniLibs = layout.buildDirectory.dir("generated/rustJniLibs/debug")
val generatedRustReleaseJniLibs = layout.buildDirectory.dir("generated/rustJniLibs/release")
val targetAbi = providers.gradleProperty("targetAbi").orNull
val releaseSigningProperties = Properties().apply {
    val propertiesFile = rootProject.file("keystore.properties")
    if (propertiesFile.isFile) {
        propertiesFile.inputStream().use(::load)
    }
}

fun releaseSigningValue(vararg names: String): String? =
    names.firstNotNullOfOrNull { name ->
        providers.gradleProperty(name).orNull
            ?: releaseSigningProperties.getProperty(name)
            ?: System.getenv(name)
    }?.takeIf { it.isNotBlank() }

val releaseStoreFile = releaseSigningValue(
    "COMICDAV_RELEASE_STORE_FILE",
    "RELEASE_STORE_FILE",
    "storeFile",
)
val releaseStorePassword = releaseSigningValue(
    "COMICDAV_RELEASE_STORE_PASSWORD",
    "RELEASE_STORE_PASSWORD",
    "storePassword",
)
val releaseKeyAlias = releaseSigningValue(
    "COMICDAV_RELEASE_KEY_ALIAS",
    "RELEASE_KEY_ALIAS",
    "keyAlias",
)
val releaseKeyPassword = releaseSigningValue(
    "COMICDAV_RELEASE_KEY_PASSWORD",
    "RELEASE_KEY_PASSWORD",
    "keyPassword",
)
val releaseSigningEntries = mapOf(
    "storeFile" to releaseStoreFile,
    "storePassword" to releaseStorePassword,
    "keyAlias" to releaseKeyAlias,
    "keyPassword" to releaseKeyPassword,
)
val releaseSigningMissing = releaseSigningEntries
    .filterValues { it.isNullOrBlank() }
    .keys
val hasReleaseSigning = releaseSigningMissing.isEmpty()

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
            abiFilters += targetAbi?.let(::listOf) ?: listOf("arm64-v8a", "x86_64")
        }
    }

    buildFeatures {
        compose = true
    }

    sourceSets {
        getByName("debug") {
            jniLibs.srcDir(generatedRustJniLibs)
        }
        getByName("release") {
            jniLibs.srcDir(generatedRustReleaseJniLibs)
        }
    }

    signingConfigs {
        create("release") {
            if (hasReleaseSigning) {
                storeFile = file(releaseStoreFile!!)
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }

    buildTypes {
        release {
            signingConfig = signingConfigs.getByName("release")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
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

tasks.register("buildRustAndroidDebug") {
    buildRustAndroidVariant(
        outputRoot = generatedRustJniLibs,
        cargoProfile = RustCargoProfile.Debug,
    )
}

tasks.register("buildRustAndroidRelease") {
    buildRustAndroidVariant(
        outputRoot = generatedRustReleaseJniLibs,
        cargoProfile = RustCargoProfile.Release,
    )
}

enum class RustCargoProfile(
    val targetDirName: String,
    val cargoArgs: List<String>,
) {
    Debug("debug", emptyList()),
    Release("release", listOf("--release")),
}

fun org.gradle.api.Task.buildRustAndroidVariant(
    outputRoot: org.gradle.api.provider.Provider<org.gradle.api.file.Directory>,
    cargoProfile: RustCargoProfile,
) {
    inputs.property("targetAbi", targetAbi ?: "all")
    inputs.property("cargoProfile", cargoProfile.targetDirName)
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
        ).filter { targetAbi == null || it.abi == targetAbi }

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
                commandLine(
                    listOf("cargo", "build", "--target", target.triple) + cargoProfile.cargoArgs,
                )
            }.result.get()

            val sourceLibrary =
                file("../comic-core/target/${target.triple}/${cargoProfile.targetDirName}/libcomic_core.so")
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

tasks.matching { it.name == "mergeReleaseJniLibFolders" }.configureEach {
    dependsOn("buildRustAndroidRelease")
}

tasks.register("checkReleaseSigning") {
    doLast {
        if (!hasReleaseSigning) {
            throw GradleException(
                "Release signing is not configured. Missing ${releaseSigningMissing.joinToString()} in " +
                    "keystore.properties, Gradle properties, or environment variables.",
            )
        }
        val store = file(releaseStoreFile!!)
        if (!store.isFile) {
            throw GradleException("Release keystore not found at ${store.absolutePath}")
        }
    }
}

tasks.matching {
    it.name == "assembleRelease" ||
        it.name == "bundleRelease" ||
        it.name == "packageRelease" ||
        it.name == "validateSigningRelease"
}.configureEach {
    dependsOn("checkReleaseSigning")
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
    val composeBom = platform("androidx.compose:compose-bom:2026.04.01")
    implementation(composeBom)
    androidTestImplementation(composeBom)

    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.datastore:datastore-preferences:1.1.1")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("io.coil-kt.coil3:coil-compose:3.0.4")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation(files("libs/fitz-1.27.1.aar"))
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("androidx.room:room-runtime:2.8.4")
    implementation("androidx.room:room-ktx:2.8.4")
    kapt("androidx.room:room-compiler:2.8.4")
    debugImplementation("androidx.compose.ui:ui-tooling")

    testImplementation("junit:junit:4.13.2")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
    testImplementation("androidx.room:room-testing:2.8.4")
    testImplementation("androidx.test:core:1.6.1")
    testImplementation("org.robolectric:robolectric:4.15.1")
    androidTestImplementation("androidx.test:core:1.6.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
}
