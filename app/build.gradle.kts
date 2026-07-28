import org.gradle.api.GradleException
import org.gradle.api.DefaultTask
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.TaskAction
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

abstract class CheckReleaseSigning : DefaultTask() {
    @get:Input
    abstract val missingEntries: ListProperty<String>

    @get:Input
    abstract val releaseStorePath: Property<String>

    @TaskAction
    fun validateSigningConfiguration() {
        val missing = missingEntries.get()
        if (missing.isNotEmpty()) {
            throw GradleException(
                "Release signing is not configured. Missing ${missing.joinToString()} in " +
                    "keystore.properties, Gradle properties, or environment variables.",
            )
        }

        val store = File(releaseStorePath.get())
        if (!store.isFile) {
            throw GradleException("Release keystore not found at ${store.absolutePath}")
        }
    }
}

val compileAndroidSdk = 36
val minAndroidSdk = 26
val targetAndroidSdk = 36
val supportedTargetAbis = setOf("arm64-v8a", "x86_64")
val targetAbiAliases = mapOf(
    "arm64_v8a" to "arm64-v8a",
)
val rawTargetAbi = providers.gradleProperty("targetAbi").orNull?.trim()?.takeIf { it.isNotBlank() }
fun normalizeTargetAbi(value: String): String = targetAbiAliases[value] ?: value
val targetAbi = rawTargetAbi?.let(::normalizeTargetAbi)
if (targetAbi != null && targetAbi !in supportedTargetAbis) {
    throw GradleException(
        "Unsupported targetAbi '$rawTargetAbi' (normalized to '$targetAbi'). " +
            "Supported values: ${supportedTargetAbis.joinToString()}",
    )
}
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
    "MUBOX_RELEASE_STORE_FILE",
    "RELEASE_STORE_FILE",
    "storeFile",
)
val releaseStorePassword = releaseSigningValue(
    "MUBOX_RELEASE_STORE_PASSWORD",
    "RELEASE_STORE_PASSWORD",
    "storePassword",
)
val releaseKeyAlias = releaseSigningValue(
    "MUBOX_RELEASE_KEY_ALIAS",
    "RELEASE_KEY_ALIAS",
    "keyAlias",
)
val releaseKeyPassword = releaseSigningValue(
    "MUBOX_RELEASE_KEY_PASSWORD",
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
    namespace = "org.mubox.reader"
    compileSdk = compileAndroidSdk

    defaultConfig {
        applicationId = "org.mubox.reader"
        minSdk = minAndroidSdk
        targetSdk = targetAndroidSdk
        versionCode = 3
        versionName = "1.2"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        ndk {
            abiFilters += targetAbi?.let(::listOf) ?: listOf("arm64-v8a", "x86_64")
        }
    }

    buildFeatures {
        compose = true
    }

    packaging {
        jniLibs {
            // API 23+ can load page-aligned native libraries directly from the APK.
            // This avoids an install-time extraction/copy and its duplicate disk usage.
            useLegacyPackaging = false
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
            isDebuggable = false
            isJniDebuggable = false
            isMinifyEnabled = true
            isShrinkResources = true
            ndk {
                debugSymbolLevel = "NONE"
            }
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

tasks.register<CheckReleaseSigning>("checkReleaseSigning") {
    missingEntries.set(releaseSigningMissing.sorted())
    releaseStorePath.set(
        releaseStoreFile
            ?.let { rootProject.file(it).absolutePath }
            .orEmpty(),
    )
}

tasks.matching {
    it.name == "assembleRelease" ||
        it.name == "bundleRelease" ||
        it.name == "packageRelease" ||
        it.name == "validateSigningRelease"
}.configureEach {
    dependsOn("checkReleaseSigning")
}

dependencies {
    implementation(project(":core:model"))
    implementation(project(":core:diagnostics"))
    implementation(project(":nativebridge"))
    implementation(project(":webdav"))
    implementation(project(":ui"))
    implementation(project(":data"))
    implementation(project(":feature:directory-listing"))
    implementation(project(":feature:file-directory"))
    implementation(project(":feature:library"))
    implementation(project(":feature:reader"))
    implementation(project(":feature:video"))
    implementation(project(":feature:downloads"))
    implementation(project(":feature:settings"))
    implementation(project(":feature:video-library"))
    implementation(project(":feature:webdav"))

    val composeBom = platform("androidx.compose:compose-bom:2026.05.01")
    implementation(composeBom)
    androidTestImplementation(composeBom)
    val okhttpBom = platform("com.squareup.okhttp3:okhttp-bom:5.3.2")
    testImplementation(okhttpBom)

    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.datastore:datastore-preferences:1.1.1")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.10.0")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("io.coil-kt.coil3:coil-compose:3.4.0")
    implementation(files("libs/fitz-1.27.1.aar"))
    implementation(files("libs/mpv-android-lib-v0.0.1.aar"))
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")

    testImplementation("junit:junit:4.13.2")
    testImplementation("com.squareup.okhttp3:mockwebserver")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.11.0")
    testImplementation("androidx.test:core:1.6.1")
    testImplementation("org.robolectric:robolectric:4.15.1")
    androidTestImplementation("androidx.test:core:1.6.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
}
