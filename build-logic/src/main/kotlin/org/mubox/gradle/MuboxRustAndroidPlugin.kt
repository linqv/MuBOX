package org.mubox.gradle

import com.android.build.api.dsl.LibraryExtension
import java.io.File
import java.util.Properties
import javax.inject.Inject
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.artifacts.VersionCatalogsExtension
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.Sync
import org.gradle.api.tasks.TaskAction
import org.gradle.process.ExecOperations

private data class RustAndroidTarget(
    val abi: String,
    val triple: String,
    val linkerName: String,
    val linkerEnvironment: String,
)

private enum class RustCargoProfile(
    val targetDirectoryName: String,
    val cargoArguments: List<String>,
    val taskName: String,
) {
    Debug("debug", emptyList(), "Debug"),
    Release("release", listOf("--release"), "Release"),
}

@CacheableTask
abstract class CompileRustAndroidLibrary @Inject constructor(
    private val execOperations: ExecOperations,
) : DefaultTask() {
    @get:Input
    abstract val targetTriple: Property<String>

    @get:Input
    abstract val linkerEnvironment: Property<String>

    @get:Input
    abstract val cargoArguments: ListProperty<String>

    @get:Input
    abstract val cargoProfileDirectory: Property<String>

    @get:Input
    abstract val libraryName: Property<String>

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val rustInputs: ConfigurableFileCollection

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val linker: RegularFileProperty

    @get:Internal
    abstract val crateDirectory: DirectoryProperty

    @get:OutputFile
    abstract val outputLibrary: RegularFileProperty

    @TaskAction
    fun compile() {
        val crateDir = crateDirectory.get().asFile
        val linkerFile = linker.get().asFile
        execOperations.exec { spec ->
            spec.workingDir(crateDir)
            spec.environment(linkerEnvironment.get(), linkerFile.absolutePath)
            spec.commandLine(
                listOf("cargo", "build", "--locked", "--target", targetTriple.get()) +
                    cargoArguments.get(),
            )
        }

        val fileName = "lib${libraryName.get()}.so"
        val sourceLibrary = crateDir.resolve(
            "target/${targetTriple.get()}/${cargoProfileDirectory.get()}/$fileName",
        )
        if (!sourceLibrary.isFile) {
            throw GradleException("Rust output not found at $sourceLibrary")
        }
        val output = outputLibrary.get().asFile
        output.parentFile.mkdirs()
        sourceLibrary.copyTo(output, overwrite = true)
    }
}

class MuboxRustAndroidPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        val rust = extensions.create("muboxRustAndroid", MuboxRustAndroidExtension::class.java)
        pluginManager.withPlugin("com.android.library") {
            afterEvaluate {
                configureRustAndroidLibrary(rust)
            }
        }
    }
}

private fun Project.configureRustAndroidLibrary(rust: MuboxRustAndroidExtension) {
    val android = extensions.getByType(LibraryExtension::class.java)
    val shared = extensions.getByType(MuboxAndroidExtension::class.java)
    val libs = extensions.getByType(VersionCatalogsExtension::class.java).named("libs")
    val ndkVersion = libs.findVersion("ndk").get().requiredVersion
    val libraryName = rust.libraryName.get()
    val crateDirectory = rust.crateDirectory.get()
    val generatedDebugLibraries = layout.buildDirectory.dir("generated/rustJniLibs/debug")
    val generatedReleaseLibraries = layout.buildDirectory.dir("generated/rustJniLibs/release")
    val intermediateLibraries = layout.buildDirectory.dir("intermediates/rustAndroid")

    android.ndkVersion = ndkVersion
    android.sourceSets.getByName("debug").jniLibs.directories.add(
        generatedDebugLibraries.get().asFile.absolutePath,
    )
    android.sourceSets.getByName("release").jniLibs.directories.add(
        generatedReleaseLibraries.get().asFile.absolutePath,
    )

    val minSdk = shared.minSdk.get()
    val targetAbi = shared.targetAbi.orNull
    val targets = listOf(
        RustAndroidTarget(
            abi = "arm64-v8a",
            triple = "aarch64-linux-android",
            linkerName = "aarch64-linux-android${minSdk}-clang",
            linkerEnvironment = "CARGO_TARGET_AARCH64_LINUX_ANDROID_LINKER",
        ),
        RustAndroidTarget(
            abi = "x86_64",
            triple = "x86_64-linux-android",
            linkerName = "x86_64-linux-android${minSdk}-clang",
            linkerEnvironment = "CARGO_TARGET_X86_64_LINUX_ANDROID_LINKER",
        ),
    ).filter { targetAbi == null || it.abi == targetAbi }

    RustCargoProfile.entries.forEach { profile ->
        val compileTasks = targets.associateWith { rustTarget ->
            val abiTaskName = rustTarget.abi
                .split('-', '_')
                .joinToString("") { part -> part.replaceFirstChar(Char::uppercaseChar) }
            tasks.register(
                "compileRustAndroid${profile.taskName}$abiTaskName",
                CompileRustAndroidLibrary::class.java,
            ) { task ->
                task.targetTriple.set(rustTarget.triple)
                task.linkerEnvironment.set(rustTarget.linkerEnvironment)
                task.cargoArguments.set(profile.cargoArguments)
                task.cargoProfileDirectory.set(profile.targetDirectoryName)
                task.libraryName.set(libraryName)
                task.crateDirectory.set(crateDirectory)
                task.rustInputs.from(
                    fileTree(crateDirectory) { patterns ->
                        patterns.include("Cargo.toml", "Cargo.lock", "build.rs", "src/**/*.rs")
                    },
                )
                task.linker.set(
                    file(
                        "${androidSdkDirectory()}/ndk/$ndkVersion/toolchains/llvm/prebuilt/" +
                            "${ndkHostTag()}/bin/${rustTarget.linkerName}${ndkExecutableSuffix()}",
                    ),
                )
                task.outputLibrary.set(
                    intermediateLibraries.map { root ->
                        root.file(
                            "${profile.targetDirectoryName}/${rustTarget.abi}/lib$libraryName.so",
                        )
                    },
                )
            }
        }
        val outputRoot = when (profile) {
            RustCargoProfile.Debug -> generatedDebugLibraries
            RustCargoProfile.Release -> generatedReleaseLibraries
        }
        tasks.register("buildRustAndroid${profile.taskName}", Sync::class.java) { sync ->
            compileTasks.forEach { (rustTarget, compileTask) ->
                sync.dependsOn(compileTask)
                sync.from(compileTask.flatMap(CompileRustAndroidLibrary::outputLibrary)) { copy ->
                    copy.into(rustTarget.abi)
                }
            }
            sync.into(outputRoot)
        }
    }

    tasks.matching { it.name == "mergeDebugJniLibFolders" }.configureEach { task ->
        task.dependsOn("buildRustAndroidDebug")
    }
    tasks.matching { it.name == "mergeReleaseJniLibFolders" }.configureEach { task ->
        task.dependsOn("buildRustAndroidRelease")
    }
}

private fun Project.androidSdkDirectory(): File {
    val localPropertiesFile = rootProject.file("local.properties")
    if (localPropertiesFile.isFile) {
        val properties = Properties()
        localPropertiesFile.inputStream().use(properties::load)
        properties.getProperty("sdk.dir")?.let(::file)?.let { return it }
    }
    System.getenv("ANDROID_HOME")?.let(::file)?.let { return it }
    System.getenv("ANDROID_SDK_ROOT")?.let(::file)?.let { return it }
    throw GradleException("Android SDK directory not configured")
}

private fun ndkHostTag(): String {
    val operatingSystem = System.getProperty("os.name").lowercase()
    val architecture = System.getProperty("os.arch").lowercase()
    return when {
        operatingSystem.contains("linux") -> "linux-x86_64"
        operatingSystem.contains("mac") && architecture in setOf("aarch64", "arm64") -> "darwin-arm64"
        operatingSystem.contains("mac") -> "darwin-x86_64"
        operatingSystem.contains("windows") -> "windows-x86_64"
        else -> throw GradleException("Unsupported Android NDK host: $operatingSystem/$architecture")
    }
}

private fun ndkExecutableSuffix(): String =
    if (System.getProperty("os.name").contains("windows", ignoreCase = true)) ".cmd" else ""
