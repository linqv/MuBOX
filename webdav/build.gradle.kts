plugins {
    id("org.jetbrains.kotlin.jvm")
    `java-library`
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    api(project(":core:model"))
    api(project(":core:diagnostics"))

    api(platform("com.squareup.okhttp3:okhttp-bom:5.3.2"))
    api("com.squareup.okhttp3:okhttp")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0")

    testImplementation(project(":nativebridge"))
    testImplementation("junit:junit:4.13.2")
    testImplementation("com.squareup.okhttp3:mockwebserver")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.11.0")
}
