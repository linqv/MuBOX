package com.example.comicdav.data

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ComicCacheAnalysisTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun analyzeComicCacheSplitsEachCacheBucket() {
        val cacheDir = temporaryFolder.newFolder("cache")
        cacheDir.writeFile("remote-comics/book.cbz", 10)
        cacheDir.writeFile("remote-comics/index/book.json", 5)
        cacheDir.writeFile("comicdav-pages/comic-a/page-0.img", 7)
        cacheDir.writeFile("library-covers/cover.img", 3)

        val analysis = analyzeComicCache(cacheDir)

        assertEquals(10L, analysis.remoteDownloadsBytes)
        assertEquals(5L, analysis.remoteIndexBytes)
        assertEquals(7L, analysis.readerPagesBytes)
        assertEquals(3L, analysis.libraryCoversBytes)
        assertEquals(25L, analysis.totalBytes)
    }

    @Test
    fun clearComicCacheCategoryDeletesOnlySelectedBucket() {
        val cacheDir = temporaryFolder.newFolder("cache")
        val remoteFile = cacheDir.writeFile("remote-comics/book.cbz", 10)
        val indexFile = cacheDir.writeFile("remote-comics/index/book.json", 5)
        val pageFile = cacheDir.writeFile("comicdav-pages/comic-a/page-0.img", 7)
        val coverFile = cacheDir.writeFile("library-covers/cover.img", 3)

        val result = clearComicCacheCategory(cacheDir, ComicCacheCategory.REMOTE_INDEX)

        assertEquals(1, result.filesDeleted)
        assertEquals(5L, result.bytesDeleted)
        assertTrue(remoteFile.exists())
        assertFalse(indexFile.exists())
        assertTrue(pageFile.exists())
        assertTrue(coverFile.exists())
    }

    private fun File.writeFile(relativePath: String, size: Int): File {
        val file = resolve(relativePath)
        file.parentFile?.mkdirs()
        file.writeBytes(ByteArray(size) { 1 })
        return file
    }
}
