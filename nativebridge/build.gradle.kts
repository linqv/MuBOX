import java.util.Properties
import javax.inject.Inject
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
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
import org.mubox.gradle.MuboxAndroidExtension

plugins {
    id("mubox.android.library")
}

data class RustAndroidTarget(
    val abi: String,
    val triple: String,
    val linkerName: String,
    val linkerEnv: String,
)

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

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val rustInputs: ConfigurableFileCollection

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val linker: RegularFileProperty

    @get:Internal
    abstract val crateDirectory: DirectoryProperty

    @get:Internal
    abstract val cargoProfileDirectory: Property<String>

    @get:OutputFile
    abstract val outputLibrary: RegularFileProperty

    @TaskAction
    fun compile() {
        val crateDir = crateDirectory.get().asFile
        val linkerFile = linker.get().asFile
        execOperations.exec {
            workingDir = crateDir
            environment(linkerEnvironment.get(), linkerFile.absolutePath)
            commandLine(
                listOf("cargo", "build", "--locked", "--target", targetTriple.get()) + cargoArguments.get(),
            )
        }

        val sourceLibrary = crateDir.resolve(
            "target/${targetTriple.get()}/${cargoProfileDirectory.get()}/libcomic_core.so",
        )
        if (!sourceLibrary.isFile) {
            throw GradleException("Rust output not found at $sourceLibrary")
        }
        val output = outputLibrary.get().asFile
        output.parentFile.mkdirs()
        sourceLibrary.copyTo(output, overwrite = true)
    }
}

val muboxAndroid = extensions.getByType<MuboxAndroidExtension>()
val minAndroidSdk = muboxAndroid.minSdk.get()
val supportedTargetAbis = muboxAndroid.supportedAbis.get().toSet()
val targetAbi = muboxAndroid.targetAbi.orNull
val androidNdkVersion = libs.versions.ndk.get()
val generatedRustJniLibs = layout.buildDirectory.dir("generated/rustJniLibs/debug")
val generatedRustReleaseJniLibs = layout.buildDirectory.dir("generated/rustJniLibs/release")

android {
    namespace = "org.mubox.reader.nativebridge"
    ndkVersion = androidNdkVersion

    defaultConfig {
        consumerProguardFiles("consumer-rules.pro")
    }

    sourceSets {
        getByName("debug") {
            jniLibs.directories.add(generatedRustJniLibs.get().asFile.absolutePath)
        }
        getByName("release") {
            jniLibs.directories.add(generatedRustReleaseJniLibs.get().asFile.absolutePath)
        }
    }
}

enum class RustCargoProfile(
    val targetDirName: String,
    val cargoArgs: List<String>,
    val taskName: String,
) {
    Debug("debug", emptyList(), "Debug"),
    Release("release", listOf("--release"), "Release"),
}

val rustAndroidTargets = listOf(
    RustAndroidTarget(
        abi = "arm64-v8a",
        triple = "aarch64-linux-android",
        linkerName = "aarch64-linux-android${minAndroidSdk}-clang",
        linkerEnv = "CARGO_TARGET_AARCH64_LINUX_ANDROID_LINKER",
    ),
    RustAndroidTarget(
        abi = "x86_64",
        triple = "x86_64-linux-android",
        linkerName = "x86_64-linux-android${minAndroidSdk}-clang",
        linkerEnv = "CARGO_TARGET_X86_64_LINUX_ANDROID_LINKER",
    ),
)
val rustAndroidIntermediate = layout.buildDirectory.dir("intermediates/rustAndroid")

fun registerRustAndroidVariant(
    outputRoot: org.gradle.api.provider.Provider<org.gradle.api.file.Directory>,
    cargoProfile: RustCargoProfile,
) {
    val selectedTargets = rustAndroidTargets.filter { targetAbi == null || it.abi == targetAbi }
    val compileTasks = selectedTargets.associateWith { target ->
        val abiTaskName = target.abi
            .split('-', '_')
            .joinToString("") { part -> part.replaceFirstChar(Char::uppercaseChar) }
        tasks.register<CompileRustAndroidLibrary>(
            "compileRustAndroid${cargoProfile.taskName}$abiTaskName",
        ) {
            targetTriple.set(target.triple)
            linkerEnvironment.set(target.linkerEnv)
            cargoArguments.set(cargoProfile.cargoArgs)
            cargoProfileDirectory.set(cargoProfile.targetDirName)
            crateDirectory.set(layout.projectDirectory.dir("../comic-core"))
            rustInputs.from(
                fileTree("../comic-core") {
                    include("Cargo.toml", "Cargo.lock", "src/**/*.rs")
                },
            )
            linker.set(
                file(
                    "${androidSdkDir()}/ndk/$androidNdkVersion/toolchains/llvm/prebuilt/" +
                        "linux-x86_64/bin/${target.linkerName}",
                ),
            )
            outputLibrary.set(
                rustAndroidIntermediate.map { root ->
                    root.file("${cargoProfile.targetDirName}/${target.abi}/libcomic_core.so")
                },
            )
        }
    }
    tasks.register<Sync>("buildRustAndroid${cargoProfile.taskName}") {
        compileTasks.forEach { (target, compileTask) ->
            dependsOn(compileTask)
            from(compileTask.flatMap(CompileRustAndroidLibrary::outputLibrary)) {
                into(target.abi)
            }
        }
        into(outputRoot)
    }
}

registerRustAndroidVariant(
    outputRoot = generatedRustJniLibs,
    cargoProfile = RustCargoProfile.Debug,
)

registerRustAndroidVariant(
    outputRoot = generatedRustReleaseJniLibs,
    cargoProfile = RustCargoProfile.Release,
)

tasks.matching { it.name == "mergeDebugJniLibFolders" }.configureEach {
    dependsOn("buildRustAndroidDebug")
}

tasks.matching { it.name == "mergeReleaseJniLibFolders" }.configureEach {
    dependsOn("buildRustAndroidRelease")
}

fun androidSdkDir(): File {
    val localPropertiesFile = rootProject.file("local.properties")
    if (localPropertiesFile.isFile) {
        val properties = Properties()
        localPropertiesFile.inputStream().use(properties::load)
        properties.getProperty("sdk.dir")?.let { return file(it) }
    }
    System.getenv("ANDROID_HOME")?.let { return file(it) }
    System.getenv("ANDROID_SDK_ROOT")?.let { return file(it) }
    throw GradleException("Android SDK directory not configured")
}

dependencies {
    implementation(project(":core:model"))
    implementation(project(":core:diagnostics"))

    compileOnly(libs.androidx.annotation)
    testImplementation(project(":webdav"))
    testImplementation(libs.androidx.annotation)
    testImplementation(libs.junit)
}
