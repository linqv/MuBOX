plugins {
    id("mubox.android.library")
    id("mubox.android.compose")
}

android {
    namespace = "org.mubox.reader.video"
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
