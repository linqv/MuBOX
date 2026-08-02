package org.mubox.reader.feature.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ReaderPageCacheTest {
    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun pruneRemovesOldestPageFilesUsingConfiguredLimitAndKeepsProtectedFile() {
        val oldFile = ReaderPageCache.pageFile(temp.root, "book", 0)
        oldFile.writeBytes(ByteArray(8) { 1 })
        oldFile.setLastModified(1_000L)
        val protectedFile = ReaderPageCache.pageFile(temp.root, "book", 1)
        protectedFile.writeBytes(ByteArray(8) { 2 })
        protectedFile.setLastModified(2_000L)

        ReaderPageCache.prune(temp.root, protectedFile = protectedFile, maxBytes = 10L)

        assertFalse(oldFile.exists())
        assertTrue(protectedFile.exists())
    }

    @Test
    fun clearComicPagesRemovesPersistentAndTransientPagesWithoutPrefixMatches() {
        val persistent = ReaderPageCache.pageFile(temp.root, "book", 0).apply {
            writeBytes(ByteArray(2))
        }
        val transient = ReaderPageCache.transientPageFile(temp.root, "book#12", 0).apply {
            writeBytes(ByteArray(5))
        }
        val similarlyPrefixed = ReaderPageCache.transientPageFile(temp.root, "book-extra#12", 0).apply {
            writeBytes(ByteArray(11))
        }

        val bytesDeleted = ReaderPageCache.clearComicPages(temp.root, "book")

        assertEquals(7L, bytesDeleted)
        assertFalse(persistent.exists())
        assertFalse(transient.exists())
        assertTrue(similarlyPrefixed.exists())
    }
}
