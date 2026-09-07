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

    @Test
    fun pageFileUsesV2VersionAndDeletesLegacyUnversionedFile() {
        val pageDir = temp.root.resolve("mubox-reader-pages/book")
        pageDir.mkdirs()
        val legacyFile = pageDir.resolve("page-0.img")
        legacyFile.writeBytes(byteArrayOf(1, 2, 3))
        assertTrue(legacyFile.exists())

        val versionedFile = ReaderPageCache.pageFile(temp.root, "book", 0)

        assertEquals("v2-page-0.img", versionedFile.name)
        assertFalse("Legacy unversioned page file should be deleted", legacyFile.exists())
        assertFalse("New versioned page file should not exist until written", versionedFile.exists())
    }

    @Test
    fun transientPageFileUsesV2VersionAndDeletesLegacyUnversionedFile() {
        val pageDir = temp.root.resolve("mubox-reader-pages-transient/book_12")
        pageDir.mkdirs()
        val legacyFile = pageDir.resolve("page-0.img")
        legacyFile.writeBytes(byteArrayOf(4, 5, 6))
        assertTrue(legacyFile.exists())

        val versionedFile = ReaderPageCache.transientPageFile(temp.root, "book#12", 0)

        assertEquals("v2-page-0.img", versionedFile.name)
        assertFalse("Legacy transient page file should be deleted", legacyFile.exists())
        assertFalse("New transient page file should not exist until written", versionedFile.exists())
    }

    @Test
    fun clearComicPagesRemovesAllVersionsOfPagesInComicDirectory() {
        val persistentV2 = ReaderPageCache.pageFile(temp.root, "book", 0).apply {
            writeBytes(ByteArray(10))
        }
        val persistentLegacy = temp.root.resolve("mubox-reader-pages/book/page-0.img").apply {
            writeBytes(ByteArray(20))
        }
        assertTrue(persistentV2.exists())
        assertTrue(persistentLegacy.exists())

        val bytesDeleted = ReaderPageCache.clearComicPages(temp.root, "book")

        assertEquals(30L, bytesDeleted)
        assertFalse(persistentV2.exists())
        assertFalse(persistentLegacy.exists())
        assertFalse(temp.root.resolve("mubox-reader-pages/book").exists())
    }
}
