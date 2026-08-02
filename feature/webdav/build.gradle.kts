plugins {
    id("mubox.android.library")
    id("mubox.android.compose")
}

android {
    namespace = "org.mubox.reader.feature.webdav"
}

dependencies {
    testImplementation(testFixtures(project(":test-support")))

    implementation(project(":core:model"))
    implementation(project(":ui:directory-listing"))
    implementation(project(":ui"))

    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.compose.ui)

    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation(libs.coroutines.android)

    testImplementation(libs.junit)
    testImplementation(libs.coroutines.test)
}
