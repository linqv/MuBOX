package com.example.comicdav.architecture

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GradleModuleDependencyRulesTest {
    @Test
    fun settingsIncludesExactlyTheApprovedArchitectureModules() {
        val settingsFile = File(repositoryRoot, "settings.gradle.kts")
        val includedModules = SETTINGS_INCLUDE_REGEX
            .findAll(settingsFile.readText())
            .map { match -> match.groupValues[1] }
            .toSet()

        assertEquals(approvedGraph.keys, includedModules)
    }

    @Test
    fun productionProjectDependenciesMatchApprovedModuleGraph() {
        val actualGraph = approvedGraph.keys.associateWith { module ->
            projectDependencies(module, testOnly = false)
        }

        assertEquals(
            "Production project dependencies changed; review the architecture direction before updating this graph.",
            approvedGraph,
            actualGraph,
        )
    }

    @Test
    fun testProjectDependenciesMatchApprovedTestEdges() {
        val actualGraph = approvedGraph.keys.associateWith { module ->
            projectDependencies(module, testOnly = true)
        }

        assertEquals(
            "Test-only project dependencies changed; do not promote them into the production graph implicitly.",
            approvedTestGraph,
            actualGraph,
        )
    }

    @Test
    fun approvedModuleGraphIsAcyclic() {
        val visiting = linkedSetOf<String>()
        val visited = mutableSetOf<String>()

        fun visit(module: String) {
            if (module in visited) return
            assertTrue(
                "Gradle module dependency cycle: ${(visiting + module).joinToString(" -> ")}",
                visiting.add(module),
            )
            approvedGraph.getValue(module).forEach(::visit)
            visiting.remove(module)
            visited.add(module)
        }

        approvedGraph.keys.forEach(::visit)
    }

    private fun projectDependencies(module: String, testOnly: Boolean): Set<String> {
        val modulePath = module.removePrefix(":").replace(':', '/')
        val buildFile = File(repositoryRoot, "$modulePath/build.gradle.kts")
        require(buildFile.isFile) { "Missing Gradle build file for $module: ${buildFile.absolutePath}" }
        return PROJECT_DEPENDENCY_REGEX
            .findAll(buildFile.readText())
            .filter { match -> match.groupValues[1].isTestConfiguration() == testOnly }
            .map { match -> match.groupValues[2] }
            .toSortedSet()
    }

    private fun String.isTestConfiguration(): Boolean =
        startsWith("test", ignoreCase = true) || startsWith("androidTest", ignoreCase = true)

    private val repositoryRoot: File by lazy {
        generateSequence(File(".").absoluteFile.normalize()) { directory -> directory.parentFile }
            .firstOrNull { directory -> File(directory, "settings.gradle.kts").isFile }
            ?: error("Could not locate repository root from ${File(".").absolutePath}")
    }

    private companion object {
        val PROJECT_DEPENDENCY_REGEX = Regex(
            "([A-Za-z][A-Za-z0-9]*)\\s*\\(\\s*project\\(\\s*\"(:[^\"]+)\"\\s*\\)\\s*\\)",
        )
        val SETTINGS_INCLUDE_REGEX = Regex("include\\(\\s*\"(:[^\"]+)\"\\s*\\)")

        val approvedGraph: Map<String, Set<String>> = linkedMapOf(
            ":app" to setOf(
                ":core:model",
                ":core:diagnostics",
                ":nativebridge",
                ":webdav",
                ":data",
                ":ui",
                ":feature:reader",
                ":feature:video",
            ),
            ":core:model" to emptySet(),
            ":core:diagnostics" to emptySet(),
            ":nativebridge" to setOf(":core:model", ":core:diagnostics"),
            ":webdav" to setOf(":core:model", ":core:diagnostics"),
            ":data" to setOf(":core:model"),
            ":ui" to setOf(":core:model"),
            ":feature:reader" to setOf(
                ":core:model",
                ":core:diagnostics",
                ":ui",
            ),
            ":feature:video" to setOf(":core:model", ":ui"),
        ).mapValues { (_, dependencies) -> dependencies.toSortedSet() }

        val approvedTestGraph: Map<String, Set<String>> = approvedGraph.keys.associateWith { module ->
            when (module) {
                ":webdav" -> sortedSetOf(":nativebridge")
                else -> emptySet()
            }
        }
    }
}
