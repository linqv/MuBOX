package org.mubox.gradle

import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.Action
import org.gradle.api.artifacts.VersionCatalogsExtension
import org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension
import org.jetbrains.kotlin.gradle.dsl.KotlinJvmCompilerOptions
import org.jetbrains.kotlin.gradle.tasks.KotlinJvmCompile

class MuboxJvmLibraryPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        pluginManager.apply("org.jetbrains.kotlin.jvm")
        pluginManager.apply("java-library")

        val javaVersion = extensions.getByType(VersionCatalogsExtension::class.java)
            .named("libs")
            .findVersion("jvm")
            .get()
            .requiredVersion
            .toInt()
        extensions.getByType(KotlinJvmProjectExtension::class.java).jvmToolchain(javaVersion)
        tasks.withType(KotlinJvmCompile::class.java).configureEach(Action { task ->
            task.compilerOptions(Action<KotlinJvmCompilerOptions> { options ->
                options.jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.fromTarget(javaVersion.toString()))
            })
        })
    }
}
