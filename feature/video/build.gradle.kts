plugins {
    id("mubox.android.library")
    id("mubox.android.compose")
    id("mubox.rust.android")
}

android {
    namespace = "org.mubox.reader.video"
    defaultConfig {
        consumerProguardFiles("consumer-rules.pro")
    }
    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            all {
                it.jvmArgs(
                    "--add-opens=java.base/java.io=ALL-UNNAMED",
                    "--add-exports=java.base/jdk.internal.access=ALL-UNNAMED",
                )
            }
        }
    }
}

muboxRustAndroid {
    crateDirectory.set(layout.projectDirectory.dir("../../media-proxy-core"))
    libraryName.set("media_proxy_core")
}

dependencies {
    implementation(project(":core:diagnostics"))
    api(project(":core:model"))
    implementation(project(":ui"))

    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.coil.compose)
    implementation(libs.coroutines.android)
    implementation(libs.mpv)

    testImplementation(libs.junit)
    testImplementation(libs.coroutines.test)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.robolectric)
    testImplementation(platform(libs.okhttp.bom))
    testImplementation(libs.mockwebserver)
}
