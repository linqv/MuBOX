import org.gradle.api.GradleException
import org.gradle.api.DefaultTask
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.TaskAction
import java.util.Properties

plugins {
    id("com.android.application")
    id("mubox.android.compose")
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

    defaultConfig {
        applicationId = "org.mubox.reader"
        targetSdk = libs.versions.targetSdk.get().toInt()
        versionCode = 3
        versionName = "1.2"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
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
}

composeCompiler {
    includeComposeMappingFile = false
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
    testImplementation(testFixtures(project(":test-support")))

    implementation(project(":core:model"))
    implementation(project(":core:diagnostics"))
    implementation(project(":nativebridge"))
    implementation(project(":webdav"))
    implementation(project(":ui"))
    implementation(project(":ui:directory-listing"))
    implementation(project(":data"))
    implementation(project(":feature:file-directory"))
    implementation(project(":feature:home"))
    implementation(project(":feature:library"))
    implementation(project(":feature:reader"))
    implementation(project(":feature:video"))
    implementation(project(":feature:downloads"))
    implementation(project(":feature:settings"))
    implementation(project(":feature:video-library"))
    implementation(project(":feature:webdav"))

    val okhttpBom = platform(libs.okhttp.bom)
    testImplementation(okhttpBom)

    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.coil.compose)
    implementation(libs.coroutines.android)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)

    testImplementation(libs.junit)
    testImplementation(libs.mockwebserver)
    testImplementation(libs.coroutines.test)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.robolectric)
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
}
