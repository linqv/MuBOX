plugins {
    alias(libs.plugins.kotlin.jvm)
    `java-gradle-plugin`
}

group = "org.mubox.buildlogic"

kotlin {
    jvmToolchain(libs.versions.jvm.get().toInt())
}

dependencies {
    implementation(libs.android.gradle.plugin)
    implementation(libs.kotlin.gradle.plugin)
    implementation(libs.kotlin.compose.gradle.plugin)
}

gradlePlugin {
    plugins {
        register("androidLibrary") {
            id = "mubox.android.library"
            implementationClass = "org.mubox.gradle.MuboxAndroidLibraryPlugin"
        }
        register("androidCompose") {
            id = "mubox.android.compose"
            implementationClass = "org.mubox.gradle.MuboxAndroidComposePlugin"
        }
        register("jvmLibrary") {
            id = "mubox.jvm.library"
            implementationClass = "org.mubox.gradle.MuboxJvmLibraryPlugin"
        }
    }
}
