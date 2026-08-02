plugins {
    id("mubox.jvm.library")
}

dependencies {
    compileOnly(libs.androidx.annotation)
    api(libs.coroutines.core)
    testImplementation(libs.junit)
}
