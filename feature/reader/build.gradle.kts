plugins {
    id("mubox.android.library")
    id("mubox.android.compose")
}

android {
    namespace = "org.mubox.reader.feature.reader"
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
    testImplementation(testFixtures(project(":test-support")))

    implementation(project(":core:model"))
    implementation(project(":core:diagnostics"))
    implementation(project(":ui"))

    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.compose.ui)

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation(libs.coil.compose)
    implementation(libs.coroutines.android)
    implementation(libs.mupdf)

    testImplementation(libs.junit)
    testImplementation(libs.coroutines.test)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.robolectric)
}
