package org.mubox.gradle

import com.android.build.api.dsl.ApplicationExtension
import com.android.build.api.dsl.LibraryExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.artifacts.VersionCatalogsExtension

class MuboxAndroidComposePlugin : Plugin<Project> {
    override fun apply(target: Project) {
        with(target) {
            pluginManager.apply("org.jetbrains.kotlin.plugin.compose")

            pluginManager.withPlugin("com.android.application") {
                val extension = extensions.getByType(ApplicationExtension::class.java)
                configureMuboxAndroid(extension)
                extension.buildFeatures.compose = true
            }
            pluginManager.withPlugin("com.android.library") {
                val extension = extensions.getByType(LibraryExtension::class.java)
                configureMuboxAndroid(extension)
                extension.buildFeatures.compose = true
            }

            val libs = extensions.getByType(VersionCatalogsExtension::class.java).named("libs")
            val composeBom = dependencies.platform(libs.findLibrary("androidx-compose-bom").get())
            dependencies.add("implementation", composeBom)
            dependencies.add("androidTestImplementation", composeBom)
        }
    }
}
