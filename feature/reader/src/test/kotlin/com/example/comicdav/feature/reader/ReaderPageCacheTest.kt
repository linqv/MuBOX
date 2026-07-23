package com.example.comicdav.feature.reader

import com.example.comicdav.core.model.media.readerImageFormatCacheKey
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
    fun pageCacheKeyOnlyAddsAvifVariantWhenAvifIsEnabled() {
        assertTrue(readerImageFormatCacheKey("book-key", avifImagesEnabled = false) == "book-key")
        assertTrue(readerImageFormatCacheKey("book-key", avifImagesEnabled = true) == "book-key-avif")
    }
}
