package org.mubox.reader.feature.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalComicCacheKeyTest {
    @Test
    fun localComicCacheKeyChangesWhenFileMetadataChanges() {
        val original = localComicCacheKey(
            prefix = "directory",
            stableId = "content://books/demo.cbz",
            size = 100,
            lastModified = 1_000,
        )
        val changedSize = localComicCacheKey(
            prefix = "directory",
            stableId = "content://books/demo.cbz",
            size = 101,
            lastModified = 1_000,
        )
        val changedModified = localComicCacheKey(
            prefix = "directory",
            stableId = "content://books/demo.cbz",
            size = 100,
            lastModified = 2_000,
        )

        assertNotEquals(original, changedSize)
        assertNotEquals(original, changedModified)
    }

    @Test
    fun localComicCacheKeyIsStableForSameIdentity() {
        val first = localComicCacheKey("library", "content://books/demo.cbz", 100, 1_000)
        val second = localComicCacheKey("library", "content://books/demo.cbz", 100, 1_000)

        assertEquals(first, second)
        assertTrue(first.startsWith("library-"))
    }
}
