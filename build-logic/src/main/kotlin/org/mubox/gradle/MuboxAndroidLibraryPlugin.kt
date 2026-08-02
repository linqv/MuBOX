package org.mubox.gradle

import com.android.build.api.dsl.LibraryExtension
import org.gradle.api.Plugin
import org.gradle.api.Project

class MuboxAndroidLibraryPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        pluginManager.apply("com.android.library")
        configureMuboxAndroid(extension = extensions.getByType(LibraryExtension::class.java))
    }
}
