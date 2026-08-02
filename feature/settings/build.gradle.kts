plugins {
    id("mubox.android.library")
    id("mubox.android.compose")
}

android {
    namespace = "org.mubox.reader.feature.settings"
}

dependencies {
    implementation(project(":core:model"))
    implementation(project(":ui"))

    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.compose.ui)

    testImplementation(libs.junit)
}
