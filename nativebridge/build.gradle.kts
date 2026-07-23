plugins {
    id("org.jetbrains.kotlin.jvm")
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(project(":core:model"))
    implementation(project(":core:diagnostics"))

    compileOnly("androidx.annotation:annotation:1.8.1")
    testImplementation("androidx.annotation:annotation:1.8.1")
    testImplementation("junit:junit:4.13.2")
}
