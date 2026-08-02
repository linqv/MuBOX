package org.mubox.reader.core.io

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class FileLruPrunerTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun reusesDirectoryStateAndSyncsKnownProtectedFile() {
        val root = temporaryFolder.newFolder("lru-state")
        val oldFile = root.resolve("old.bin").apply {
            writeBytes(ByteArray(8) { 1 })
            setLastModified(1_000L)
        }
        val survivor = root.resolve("survivor.bin").apply {
            writeBytes(ByteArray(1) { 2 })
            setLastModified(2_000L)
        }
        FileLruPruner.prune(root, maxBytes = 100L, protectedFiles = setOf(survivor))

        val newFile = root.resolve("new.bin").apply {
            writeBytes(ByteArray(8) { 3 })
            setLastModified(3_000L)
        }
        val removed = FileLruPruner.prune(root, maxBytes = 10L, protectedFiles = setOf(newFile))

        assertEquals(1, removed)
        assertFalse(oldFile.exists())
        assertTrue(survivor.exists())
        assertTrue(newFile.exists())
    }

    @Test
    fun refreshesDirectoryStateWhenNoProtectedFilesAreProvided() {
        val root = temporaryFolder.newFolder("lru-refresh")
        val first = root.resolve("first.bin").apply {
            writeBytes(ByteArray(8) { 1 })
            setLastModified(2_000L)
        }
        FileLruPruner.prune(root, maxBytes = 100L, protectedFiles = setOf(first))
        val externallyAdded = root.resolve("external.bin").apply {
            writeBytes(ByteArray(8) { 2 })
            setLastModified(1_000L)
        }

        val removed = FileLruPruner.prune(root, maxBytes = 10L)

        assertEquals(1, removed)
        assertTrue(first.exists())
        assertFalse(externallyAdded.exists())
    }
}
