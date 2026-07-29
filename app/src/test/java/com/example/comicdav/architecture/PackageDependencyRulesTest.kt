package com.example.comicdav.architecture

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PackageDependencyRulesTest {
    @Test
    fun physicalVideoNamespaceIsClassifiedAsAFeature() {
        assertEquals("video", VIDEO_PACKAGE.featureName())
        assertEquals("video", "$VIDEO_PACKAGE.player".featureName())
    }

    @Test
    fun readerDiagnosticBridgeIsOnlyUsedAtTheCompositionRoot() {
        val outsideReaderFeature = dependencies.filter { dependency ->
            dependency.reference == "$FEATURE_PACKAGE.reader.ReaderDiagnosticLog" &&
                dependency.sourcePackage.featureName() != "reader"
        }

        assertEquals(
            "Application code should inject core Diagnostics instead of using the reader-owned global bridge.",
            setOf("app/src/main/java/com/example/comicdav/AppContainer.kt"),
            outsideReaderFeature.map(DependencyReference::sourcePath).toSet(),
        )
    }

    @Test
    fun videoActionFacadeDoesNotOwnPlaybackOrExtractionInfrastructure() {
        val facade = sourceFiles.single { sourceFile ->
            sourceFile.relativePath == "app/src/main/java/com/example/comicdav/AppVideoActions.kt"
        }
        val forbiddenReferences = setOf(
            "VideoPlayerActivity",
            "VideoProxyManager",
            "startWebDavVideoPlayback",
            "extractFromContentUri",
            "extractFromUrl",
        ).filter(facade.source::contains)

        assertTrue(
            "AppVideoActions must remain an orchestration facade; move infrastructure to its collaborators: " +
                forbiddenReferences.joinToString(),
            forbiddenReferences.isEmpty(),
        )
    }

    @Test
    fun sourceScanIncludesEveryPhysicalArchitectureModule() {
        val scannedPaths = sourceFiles.map(SourceFile::relativePath)

        REQUIRED_PHYSICAL_MODULE_PATHS.forEach { modulePath ->
            assertTrue(
                "Architecture scan missed sources from $modulePath",
                scannedPaths.any { path -> path.startsWith("$modulePath/src/main/") },
            )
        }
    }

    @Test
    fun logicalFeaturesMatchTheirApprovedPhysicalModules() {
        val misplacedSources = sourceFiles.mapNotNull { sourceFile ->
            val featureName = sourceFile.packageName.featureName()
            val modulePath = sourceFile.relativePath.modulePath()
            val expectedModulePath = featureName?.let(APPROVED_FEATURE_MODULE_PATHS::get)
            when {
                featureName != null && expectedModulePath == null ->
                    "${sourceFile.relativePath}: unapproved logical feature '$featureName'"
                expectedModulePath != null && modulePath != expectedModulePath ->
                    "${sourceFile.relativePath}: feature '$featureName' belongs in $expectedModulePath"
                modulePath in APPROVED_FEATURE_MODULE_PATHS.values &&
                    featureName != APPROVED_FEATURE_MODULE_PATHS.entries
                        .first { (_, path) -> path == modulePath }
                        .key ->
                    "${sourceFile.relativePath}: package does not match physical feature module $modulePath"
                else -> null
            }
        }

        assertTrue(
            buildString {
                appendLine("Logical feature packages must live in their approved physical modules.")
                misplacedSources.forEach { violation -> appendLine("  - $violation") }
            },
            misplacedSources.isEmpty(),
        )
    }

    @Test
    fun dataNetworkAndNativeBridgeFeatureDependenciesMatchDebtLedger() {
        val violations = dependencies.filter { dependency ->
            dependency.sourcePath.modulePath() in LOWER_LAYER_MODULE_PATHS &&
                dependency.targetPackage.featureName() != null
        }

        assertDebtLedgerMatches(
            rule = "data/network/nativebridge -> feature",
            violations = violations,
            expectedDebt = LOWER_LAYER_TO_FEATURE_DEBT,
        )
    }

    @Test
    fun logicalFeaturePackagesDoNotImportAdapterImplementations() {
        val violations = dependencies.filter { dependency ->
            dependency.sourcePackage.featureName() != null &&
                dependency.targetPackage.packageArea() in ADAPTER_PACKAGE_AREAS
        }

        assertDebtLedgerMatches(
            rule = "logical feature package -> data/network/nativebridge adapter",
            violations = violations,
            expectedDebt = FEATURE_TO_ADAPTER_DEBT,
        )
    }

    @Test
    fun crossFeatureDependenciesMatchWhitelistAndDebtLedger() {
        val violations = dependencies.filter { dependency ->
            val sourceFeature = dependency.sourcePackage.featureName()
            val targetFeature = dependency.targetPackage.featureName()
            sourceFeature != null &&
                targetFeature != null &&
                sourceFeature != targetFeature &&
                FeatureEdge(sourceFeature, targetFeature) !in PERMANENT_FEATURE_WHITELIST
        }

        assertDebtLedgerMatches(
            rule = "feature:* -> feature:* outside the permanent whitelist",
            violations = violations,
            expectedDebt = CROSS_FEATURE_DEBT,
        )
    }

    @Test
    fun appRootDependenciesMatchDebtLedger() {
        val violations = dependencies.filter { dependency ->
            (
                dependency.sourcePath.modulePath() != "app" ||
                    dependency.sourcePackage.packageArea() in APP_ROOT_RESTRICTED_AREAS
            ) &&
                dependency.targetPackage == BASE_PACKAGE
        }

        assertDebtLedgerMatches(
            rule = "feature/data/video -> app root",
            violations = violations,
            expectedDebt = APP_ROOT_DEBT,
        )
    }

    private val sourceFiles: List<SourceFile> by lazy {
        val repositoryRoot = locateRepositoryRoot()
        mainSourceRoots(repositoryRoot)
            .asSequence()
            .flatMap { sourceRoot -> sourceRoot.walkTopDown() }
            .filter { file -> file.isFile && file.extension in SOURCE_EXTENSIONS }
            .map { file ->
                val source = file.readText()
                val packageName = requireNotNull(PACKAGE_REGEX.find(source)?.groupValues?.get(1)) {
                    "Missing package declaration in ${file.absolutePath}"
                }
                SourceFile(
                    relativePath = file.relativeTo(repositoryRoot).invariantSeparatorsPath,
                    packageName = packageName,
                    source = source,
                )
            }
            .sortedBy(SourceFile::relativePath)
            .toList()
    }

    private val dependencies: List<DependencyReference> by lazy {
        val knownPackages = sourceFiles
            .map(SourceFile::packageName)
            .distinct()
            .sortedByDescending(String::length)

        sourceFiles.flatMap { sourceFile ->
            sourceFile.dependencyReferences(knownPackages)
        }.distinctBy { dependency ->
            dependency.key
        }.sortedWith(
            compareBy(
                DependencyReference::sourcePath,
                DependencyReference::reference,
            ),
        )
    }

    private fun SourceFile.dependencyReferences(
        knownPackages: List<String>,
    ): List<DependencyReference> {
        val imports = IMPORT_REGEX.findAll(source).mapNotNull { match ->
            dependencyReference(
                reference = match.groupValues[1],
                offset = match.range.first,
                knownPackages = knownPackages,
            )
        }

        val codeWithoutCommentsOrLiterals = maskCommentsAndLiterals(source)
            .maskMatches(PACKAGE_REGEX)
            .maskMatches(IMPORT_REGEX)
        val fullyQualifiedReferences = PROJECT_REFERENCE_REGEX
            .findAll(codeWithoutCommentsOrLiterals)
            .mapNotNull { match ->
                dependencyReference(
                    reference = match.value,
                    offset = match.range.first,
                    knownPackages = knownPackages,
                )
            }

        return (imports + fullyQualifiedReferences).toList()
    }

    private fun SourceFile.dependencyReference(
        reference: String,
        offset: Int,
        knownPackages: List<String>,
    ): DependencyReference? {
        val targetPackage = knownPackages.firstOrNull { packageName ->
            reference == packageName || reference.startsWith("$packageName.")
        } ?: return null

        return DependencyReference(
            sourcePath = relativePath,
            sourcePackage = packageName,
            targetPackage = targetPackage,
            reference = reference,
            line = source.lineNumberAt(offset),
        )
    }

    private fun assertDebtLedgerMatches(
        rule: String,
        violations: List<DependencyReference>,
        expectedDebt: Set<DependencyKey>,
    ) {
        val actualByKey = violations.associateBy(DependencyReference::key)
        val actual = actualByKey.keys
        val unexpected = actual - expectedDebt
        val resolved = expectedDebt - actual
        val matches = unexpected.isEmpty() && resolved.isEmpty()
        val message = buildString {
            appendLine("Package dependency debt changed for $rule.")
            if (unexpected.isNotEmpty()) {
                appendLine("Unexpected dependencies (move the contract or explicitly review the debt ledger):")
                unexpected.sortedKeys().forEach { key ->
                    val dependency = checkNotNull(actualByKey[key])
                    appendLine(
                        "  + ${dependency.sourcePath}:${dependency.line} -> " +
                            "${dependency.reference} [${dependency.targetPackage}]",
                    )
                }
            }
            if (resolved.isNotEmpty()) {
                appendLine("Resolved dependencies (remove these stale debt entries):")
                resolved.sortedKeys().forEach { key ->
                    appendLine("  - ${key.sourcePath} -> ${key.reference}")
                }
            }
        }

        assertTrue(message, matches)
    }

    private fun locateRepositoryRoot(): File =
        generateSequence(File(".").absoluteFile.normalize()) { directory -> directory.parentFile }
            .firstOrNull { directory -> File(directory, "settings.gradle.kts").isFile }
            ?: error("Could not locate repository root from ${File(".").absolutePath}")

    private fun mainSourceRoots(repositoryRoot: File): List<File> =
        ARCHITECTURE_MODULE_PATHS.flatMap { modulePath ->
            listOf(
                File(repositoryRoot, "$modulePath/src/main/java"),
                File(repositoryRoot, "$modulePath/src/main/kotlin"),
            )
        }.filter(File::isDirectory)

    private fun String.packageArea(): String? {
        val prefix = "$BASE_PACKAGE."
        if (!startsWith(prefix)) return null
        return removePrefix(prefix).substringBefore('.')
    }

    private fun String.modulePath(): String? =
        ARCHITECTURE_MODULE_PATHS.firstOrNull { modulePath -> startsWith("$modulePath/src/main/") }

    private fun String.featureName(): String? {
        if (this == VIDEO_PACKAGE || startsWith("$VIDEO_PACKAGE.")) return "video"
        val prefix = "$FEATURE_PACKAGE."
        if (!startsWith(prefix)) return null
        return removePrefix(prefix).substringBefore('.').takeIf(String::isNotBlank)
    }

    private fun String.lineNumberAt(offset: Int): Int =
        1 + take(offset).count { character -> character == '\n' }

    private fun String.maskMatches(regex: Regex): String =
        regex.replace(this) { match ->
            match.value.map { character ->
                if (character == '\n' || character == '\r') character else ' '
            }.joinToString(separator = "")
        }

    private fun maskCommentsAndLiterals(source: String): String {
        val masked = source.toCharArray()

        fun mask(index: Int) {
            if (masked[index] != '\n' && masked[index] != '\r') {
                masked[index] = ' '
            }
        }

        fun maskRange(start: Int, endExclusive: Int) {
            for (index in start until minOf(endExclusive, masked.size)) {
                mask(index)
            }
        }

        var index = 0
        while (index < source.length) {
            when {
                source.startsWith("//", index) -> {
                    maskRange(index, index + 2)
                    index += 2
                    while (index < source.length && source[index] != '\n') {
                        mask(index)
                        index += 1
                    }
                }

                source.startsWith("/*", index) -> {
                    var depth = 1
                    maskRange(index, index + 2)
                    index += 2
                    while (index < source.length && depth > 0) {
                        when {
                            source.startsWith("/*", index) -> {
                                depth += 1
                                maskRange(index, index + 2)
                                index += 2
                            }

                            source.startsWith("*/", index) -> {
                                depth -= 1
                                maskRange(index, index + 2)
                                index += 2
                            }

                            else -> {
                                mask(index)
                                index += 1
                            }
                        }
                    }
                }

                source.startsWith("\"\"\"", index) -> {
                    maskRange(index, index + 3)
                    index += 3
                    while (index < source.length && !source.startsWith("\"\"\"", index)) {
                        mask(index)
                        index += 1
                    }
                    if (index < source.length) {
                        maskRange(index, index + 3)
                        index += 3
                    }
                }

                source[index] == '"' -> {
                    mask(index)
                    index += 1
                    while (index < source.length) {
                        when (source[index]) {
                            '\\' -> {
                                maskRange(index, index + 2)
                                index += 2
                            }

                            '"' -> {
                                mask(index)
                                index += 1
                                break
                            }

                            else -> {
                                mask(index)
                                index += 1
                            }
                        }
                    }
                }

                source[index] == '\'' -> {
                    mask(index)
                    index += 1
                    while (index < source.length) {
                        when (source[index]) {
                            '\\' -> {
                                maskRange(index, index + 2)
                                index += 2
                            }

                            '\'' -> {
                                mask(index)
                                index += 1
                                break
                            }

                            else -> {
                                mask(index)
                                index += 1
                            }
                        }
                    }
                }

                else -> index += 1
            }
        }

        return masked.concatToString()
    }

    private data class SourceFile(
        val relativePath: String,
        val packageName: String,
        val source: String,
    )

    private data class DependencyReference(
        val sourcePath: String,
        val sourcePackage: String,
        val targetPackage: String,
        val reference: String,
        val line: Int,
    ) {
        val key: DependencyKey = DependencyKey(sourcePath, reference)
    }

    private data class DependencyKey(
        val sourcePath: String,
        val reference: String,
    )

    private data class FeatureEdge(
        val sourceFeature: String,
        val targetFeature: String,
    )

    private companion object {
        const val BASE_PACKAGE = "com.example.comicdav"
        const val FEATURE_PACKAGE = "$BASE_PACKAGE.feature"
        const val VIDEO_PACKAGE = "$BASE_PACKAGE.video"
        const val IDENTIFIER = "[A-Za-z_][A-Za-z0-9_]*"

        val PACKAGE_REGEX = Regex(
            "(?m)^[ \\t]*package[ \\t]+" +
                "($IDENTIFIER(?:\\.$IDENTIFIER)*)[ \\t]*;?[ \\t]*\\r?$",
        )
        val IMPORT_REGEX = Regex(
            "(?m)^[ \\t]*import[ \\t]+(?:static[ \\t]+)?" +
                "($IDENTIFIER(?:\\.(?:$IDENTIFIER|\\*))+)(?:[ \\t]+as[ \\t]+$IDENTIFIER)?" +
                "[ \\t]*;?[ \\t]*\\r?$",
        )
        val PROJECT_REFERENCE_REGEX = Regex(
            "(?<![A-Za-z0-9_])com\\.example\\.comicdav(?:\\.$IDENTIFIER)+",
        )

        val SOURCE_EXTENSIONS = setOf("kt", "java")
        val LOWER_LAYER_MODULE_PATHS = setOf("data", "webdav", "nativebridge")
        val ADAPTER_PACKAGE_AREAS = setOf("data", "network", "nativebridge", "security")
        val APP_ROOT_RESTRICTED_AREAS = setOf("feature", "data", "video")

        val PERMANENT_FEATURE_WHITELIST = setOf(
            FeatureEdge("filedirectory", "directorylisting"),
            FeatureEdge("webdav", "directorylisting"),
        )

        val LOWER_LAYER_TO_FEATURE_DEBT: Set<DependencyKey> = emptySet()
        val CROSS_FEATURE_DEBT: Set<DependencyKey> = emptySet()
        val FEATURE_TO_ADAPTER_DEBT: Set<DependencyKey> = emptySet()
        val APP_ROOT_DEBT: Set<DependencyKey> = emptySet()

        val APPROVED_FEATURE_MODULE_PATHS = mapOf(
            "directorylisting" to "feature/directory-listing",
            "filedirectory" to "feature/file-directory",
            "home" to "feature/home",
            "library" to "feature/library",
            "reader" to "feature/reader",
            "video" to "feature/video",
            "downloads" to "feature/downloads",
            "settings" to "feature/settings",
            "videolibrary" to "feature/video-library",
            "webdav" to "feature/webdav",
        )

        val ARCHITECTURE_MODULE_PATHS = listOf(
            "app",
            "core/model",
            "core/diagnostics",
            "nativebridge",
            "webdav",
            "data",
            "ui",
            "feature/directory-listing",
            "feature/file-directory",
            "feature/home",
            "feature/library",
            "feature/reader",
            "feature/video",
            "feature/downloads",
            "feature/settings",
            "feature/video-library",
            "feature/webdav",
        )

        val REQUIRED_PHYSICAL_MODULE_PATHS = setOf(
            "app",
            "core/model",
            "core/diagnostics",
            "nativebridge",
            "webdav",
            "data",
            "ui",
            "feature/directory-listing",
            "feature/file-directory",
            "feature/home",
            "feature/library",
            "feature/reader",
            "feature/video",
            "feature/downloads",
            "feature/settings",
            "feature/video-library",
            "feature/webdav",
        )

        fun dependencyKey(relativePath: String, reference: String): DependencyKey =
            DependencyKey(
                sourcePath = "com/example/comicdav/$relativePath",
                reference = "$BASE_PACKAGE.$reference",
            )

        fun Set<DependencyKey>.sortedKeys(): List<DependencyKey> =
            sortedWith(compareBy(DependencyKey::sourcePath, DependencyKey::reference))
    }
}
