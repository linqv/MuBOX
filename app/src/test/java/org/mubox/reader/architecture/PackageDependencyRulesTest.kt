package org.mubox.reader.architecture

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class PackageDependencyRulesTest {
    @Test
    fun legacyBrandNamesAreAbsent() {
        val violations = repositoryFiles.flatMap { file ->
            val relativePath = file.relativeTo(repositoryRoot).invariantSeparatorsPath
            buildList {
                if (LEGACY_BRAND_REGEX.containsMatchIn(relativePath)) {
                    add("$relativePath: legacy brand in path")
                }
                file.useLines { lines ->
                    lines.forEachIndexed { index, line ->
                        if (LEGACY_BRAND_REGEX.containsMatchIn(line)) {
                            add("$relativePath:${index + 1}: legacy brand in content")
                        }
                    }
                }
            }
        }

        assertNoViolations(
            "Legacy product naming must not remain in the repository.",
            violations,
        )
    }

    @Test
    fun ownedPackagesLiveInTheirApprovedModules() {
        val violations = sourceFiles.mapNotNull { sourceFile ->
            val expectedModule = sourceFile.packageName.approvedModule()
            val physicalOwner = sourceFile.modulePath in OWNED_MODULES
            when {
                sourceFile.packageName.isLogicalFeature() && expectedModule == null ->
                    "${sourceFile.relativePath}: unapproved feature package ${sourceFile.packageName}"

                expectedModule != null && sourceFile.modulePath != expectedModule ->
                    "${sourceFile.relativePath}: ${sourceFile.packageName} belongs in $expectedModule"

                physicalOwner && expectedModule != sourceFile.modulePath ->
                    "${sourceFile.relativePath}: package does not belong in ${sourceFile.modulePath}"

                else -> null
            }
        }

        assertNoViolations(
            "App-root, data, shared directory-listing, and feature packages must live in their approved modules.",
            violations,
        )
    }

    @Test
    fun videoActionFacadeStaysAThinOrchestrationBoundary() {
        assertNoReferences(
            paths = setOf(APP_VIDEO_ACTIONS_PATH),
            forbidden = setOf(
                "VideoPlayerActivity",
                "VideoProxyManager",
                "startWebDavVideoPlayback",
                "extractFromContentUri",
                "extractFromUrl",
            ),
            message = "AppVideoActions must delegate playback and extraction infrastructure.",
        )
        assertNoReferences(
            paths = setOf(APP_VIDEO_ACTIONS_PATH, APP_VIDEO_DEPENDENCIES_PATH),
            forbidden = setOf("AppContainer", "AppViewModels"),
            message = "Video composition must receive narrow dependencies, not app-wide aggregates.",
        )
    }

    private fun assertNoReferences(
        paths: Collection<String>,
        forbidden: Set<String>,
        message: String,
    ) {
        val matchingSources = sourceFiles.filter { sourceFile -> sourceFile.relativePath in paths }
        require(matchingSources.size == paths.size) {
            "Missing architecture source: ${(paths - matchingSources.map(SourceFile::relativePath).toSet()).joinToString()}"
        }
        val violations = matchingSources.flatMap { sourceFile ->
            forbidden.filter(sourceFile.source::contains).map { reference ->
                "${sourceFile.relativePath}: $reference"
            }
        }
        assertNoViolations(message, violations)
    }

    private fun assertNoViolations(message: String, violations: List<String>) {
        assertTrue(
            buildString {
                appendLine(message)
                violations.forEach { violation -> appendLine("  - $violation") }
            },
            violations.isEmpty(),
        )
    }

    private val sourceFiles: List<SourceFile> by lazy {
        repositoryFiles.asSequence()
            .filter { file -> file.isFile && file.extension in SOURCE_EXTENSIONS }
            .mapNotNull { file ->
                val relativePath = file.relativeTo(repositoryRoot).invariantSeparatorsPath
                val modulePath = MAIN_SOURCE_PATH_REGEX.matchEntire(relativePath)
                    ?.groupValues
                    ?.get(1)
                    ?: return@mapNotNull null
                val source = file.readText()
                val packageName = requireNotNull(PACKAGE_REGEX.find(source)?.groupValues?.get(1)) {
                    "Missing package declaration in ${file.absolutePath}"
                }
                SourceFile(relativePath, modulePath, packageName, source)
            }
            .sortedBy(SourceFile::relativePath)
            .toList()
    }

    private val repositoryFiles: List<File> by lazy {
        repositoryRoot.walkTopDown()
            .onEnter { directory ->
                directory == repositoryRoot ||
                    (directory.name !in IGNORED_DIRECTORIES && !File(directory, ".git").exists())
            }
            .filter(File::isFile)
            .filter { file -> file.extension in CHECKED_TEXT_EXTENSIONS || file.name in CHECKED_TEXT_FILES }
            .toList()
    }

    private val repositoryRoot: File by lazy {
        generateSequence(File(".").absoluteFile.normalize()) { directory -> directory.parentFile }
            .firstOrNull { directory -> File(directory, "settings.gradle.kts").isFile }
            ?: error("Could not locate repository root from ${File(".").absolutePath}")
    }

    private fun String.approvedModule(): String? = when {
        this == BASE_PACKAGE -> "app"
        this == DATA_PACKAGE || startsWith("$DATA_PACKAGE.") -> "data"
        this == DIRECTORY_LISTING_PACKAGE || startsWith("$DIRECTORY_LISTING_PACKAGE.") ->
            DIRECTORY_LISTING_MODULE
        this == VIDEO_PACKAGE || startsWith("$VIDEO_PACKAGE.") -> "feature/video"
        isLogicalFeature() -> APPROVED_FEATURE_MODULES[removePrefix("$FEATURE_PACKAGE.").substringBefore('.')]
        else -> null
    }

    private fun String.isLogicalFeature(): Boolean = startsWith("$FEATURE_PACKAGE.")

    private data class SourceFile(
        val relativePath: String,
        val modulePath: String,
        val packageName: String,
        val source: String,
    )

    private companion object {
        const val BASE_PACKAGE = "org.mubox.reader"
        const val DATA_PACKAGE = "$BASE_PACKAGE.data"
        const val FEATURE_PACKAGE = "$BASE_PACKAGE.feature"
        const val VIDEO_PACKAGE = "$BASE_PACKAGE.video"
        const val DIRECTORY_LISTING_PACKAGE = "$BASE_PACKAGE.ui.directorylisting"
        const val DIRECTORY_LISTING_MODULE = "ui/directory-listing"
        const val APP_VIDEO_ACTIONS_PATH = "app/src/main/java/org/mubox/reader/AppVideoActions.kt"
        const val APP_VIDEO_DEPENDENCIES_PATH =
            "app/src/main/java/org/mubox/reader/AppVideoActionDependencies.kt"

        val PACKAGE_REGEX = Regex("(?m)^\\s*package\\s+([A-Za-z_][A-Za-z0-9_.]*)")
        val MAIN_SOURCE_PATH_REGEX = Regex("(.+)/src/main/(?:java|kotlin)/.+")
        val SOURCE_EXTENSIONS = setOf("kt", "java")
        val CHECKED_TEXT_EXTENSIONS = SOURCE_EXTENSIONS + setOf("kts", "rs", "xml", "pro", "md")
        val CHECKED_TEXT_FILES = setOf("README", "NOTICE")
        val IGNORED_DIRECTORIES = setOf(".git", ".gradle", "build", "target")
        val LEGACY_BRAND_REGEX = Regex("comic" + "[._ /-]?" + "dav", RegexOption.IGNORE_CASE)

        val APPROVED_FEATURE_MODULES = mapOf(
            "filedirectory" to "feature/file-directory",
            "home" to "feature/home",
            "library" to "feature/library",
            "reader" to "feature/reader",
            "downloads" to "feature/downloads",
            "settings" to "feature/settings",
            "videolibrary" to "feature/video-library",
            "webdav" to "feature/webdav",
        )

        val OWNED_MODULES = APPROVED_FEATURE_MODULES.values +
            setOf("feature/video", DIRECTORY_LISTING_MODULE)
    }
}
