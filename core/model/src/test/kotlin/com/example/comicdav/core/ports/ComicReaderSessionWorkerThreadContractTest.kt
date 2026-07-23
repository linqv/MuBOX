package com.example.comicdav.core.ports

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ComicReaderSessionWorkerThreadContractTest {
    @Test
    fun blockingSessionOperationsRemainWorkerThreadAnnotated() {
        val source = File("src/main/kotlin/com/example/comicdav/core/ports/ComicReaderSession.kt")
        val lines = source.readLines()
        val signatures = listOf("fun loadPageToFile", "fun updateViewport", "fun plannedRanges")

        signatures.forEach { signature -> assertWorkerThreadAbove(lines, signature) }

        val prefetchIndexes = lines.withIndex()
            .filter { it.value.contains("fun prefetchRange") }
            .map { it.index }
        assertEquals(2, prefetchIndexes.size)
        prefetchIndexes.forEach { index -> assertWorkerThreadAbove(lines, index) }
    }

    private fun assertWorkerThreadAbove(lines: List<String>, signature: String) {
        val index = lines.indexOfFirst { it.contains(signature) }
        assertTrue("Could not find '$signature'", index > 0)
        assertWorkerThreadAbove(lines, index)
    }

    private fun assertWorkerThreadAbove(lines: List<String>, index: Int) {
        val preceding = lines.subList(maxOf(0, index - 3), index).joinToString("\n")
        assertTrue("@WorkerThread missing near line ${index + 1}", preceding.contains("@WorkerThread"))
    }
}
