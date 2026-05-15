package com.example.comicdav.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class LocalComicImportCacheTest {
    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun pruneRemovesOldestLocalImportsAndKeepsProtectedFile() {
        val oldFile = LocalComicImportCache.targetFile(temp.root, nowMs = 1_000L)
        oldFile.parentFile?.mkdirs()
        oldFile.writeBytes(ByteArray(8) { 1 })
        oldFile.setLastModified(1_000L)
        val protectedFile = LocalComicImportCache.targetFile(temp.root, nowMs = 2_000L)
        protectedFile.writeBytes(ByteArray(8) { 2 })
        protectedFile.setLastModified(2_000L)

        LocalComicImportCache.prune(temp.root, maxBytes = 10L, protectedFile = protectedFile)

        assertFalse(oldFile.exists())
        assertTrue(protectedFile.exists())
    }
}
