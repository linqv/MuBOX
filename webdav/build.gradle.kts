plugins {
    id("mubox.jvm.library")
}

dependencies {
    testImplementation(testFixtures(project(":test-support")))

    api(project(":core:model"))
    api(project(":core:diagnostics"))

    implementation(platform(libs.okhttp.bom))
    implementation(libs.okhttp)
    implementation(libs.coroutines.core)

    testImplementation(libs.junit)
    testImplementation(libs.mockwebserver)
    testImplementation(libs.coroutines.test)
}
