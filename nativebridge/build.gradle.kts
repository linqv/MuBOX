plugins {
    id("mubox.android.library")
    id("mubox.rust.android")
}

android {
    namespace = "org.mubox.reader.nativebridge"

    defaultConfig {
        consumerProguardFiles("consumer-rules.pro")
    }
}

muboxRustAndroid {
    crateDirectory.set(layout.projectDirectory.dir("../comic-core"))
    libraryName.set("comic_core")
}

dependencies {
    implementation(project(":core:model"))
    implementation(project(":core:diagnostics"))

    compileOnly(libs.androidx.annotation)
    testImplementation(project(":webdav"))
    testImplementation(libs.androidx.annotation)
    testImplementation(libs.junit)
}
