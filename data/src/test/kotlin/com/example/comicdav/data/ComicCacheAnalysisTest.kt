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
        val codeCacheDir = temporaryFolder.newFolder("code-cache")
        val externalCacheDir = temporaryFolder.newFolder("external-cache")
        cacheDir.writeFile("remote-comics/book.cbz", 10)
        cacheDir.writeFile("remote-comics/index/book.json", 5)
        cacheDir.writeFile("comicdav-pages/comic-a/page-0.img", 7)
        cacheDir.writeFile("comicdav-pages-transient/comic-a/page-1.img", 11)
        cacheDir.writeFile("library-covers/cover.img", 3)
        cacheDir.writeFile("video-library-thumbnails/video.jpg", 13)
        cacheDir.writeFile("video-subtitles/subtitle.ass", 17)
        cacheDir.writeFile("image_cache/coil-entry", 19)
        codeCacheDir.writeFile("compiled/entry", 23)
        externalCacheDir.writeFile("external-entry", 29)

        val analysis = analyzeComicCache(cacheDir, codeCacheDir, listOf(externalCacheDir))

        assertEquals(10L, analysis.remoteDownloadsBytes)
        assertEquals(5L, analysis.remoteIndexBytes)
        assertEquals(7L, analysis.readerPagesBytes)
        assertEquals(11L, analysis.transientReaderPagesBytes)
        assertEquals(3L, analysis.libraryCoversBytes)
        assertEquals(13L, analysis.videoThumbnailsBytes)
        assertEquals(17L, analysis.videoSubtitlesBytes)
        assertEquals(23L, analysis.codeCacheBytes)
        assertEquals(29L, analysis.externalCacheBytes)
        assertEquals(19L, analysis.otherBytes)
        assertEquals(137L, analysis.totalBytes)
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

    @Test
    fun clearComicCacheDeletesEveryFileUnderCacheDirectory() {
        val remote = temporaryFolder.root.resolve("remote-comics/book.cbz").apply {
            parentFile!!.mkdirs()
            writeBytes(ByteArray(10))
        }
        val page = temporaryFolder.root.resolve("comicdav-pages/book/page-1.img").apply {
            parentFile!!.mkdirs()
            writeBytes(ByteArray(20))
        }
        val unrelated = temporaryFolder.root.resolve("other/remove.txt").apply {
            parentFile!!.mkdirs()
            writeText("keep")
        }

        val deleted = clearComicCache(temporaryFolder.root)

        assertEquals(3, deleted.filesDeleted)
        assertEquals(34, deleted.bytesDeleted)
        assertFalse(remote.exists())
        assertFalse(page.exists())
        assertFalse(unrelated.exists())
        assertTrue(temporaryFolder.root.exists())
    }

    @Test
    fun otherCategoryClearsOnlyUnclassifiedCacheFiles() {
        val cacheDir = temporaryFolder.newFolder("cache-with-other")
        val known = cacheDir.writeFile("comicdav-pages/book/page-1.img", 20)
        val other = cacheDir.writeFile("third-party-cache/entry", 30)
        val rootFile = cacheDir.writeFile("orphan.tmp", 40)

        val result = clearComicCacheCategory(cacheDir, ComicCacheCategory.OTHER)

        assertEquals(2, result.filesDeleted)
        assertEquals(70L, result.bytesDeleted)
        assertTrue(known.exists())
        assertFalse(other.exists())
        assertFalse(rootFile.exists())
    }

    @Test
    fun formatsCacheSizesForSettingsUi() {
        assertEquals("0 B", formatCacheSize(0))
        assertEquals("512 B", formatCacheSize(512))
        assertEquals("1.5 MB", formatCacheSize(1_572_864))
        assertEquals("2.0 GB", formatCacheSize(2_147_483_648))
    }

    private fun File.writeFile(relativePath: String, size: Int): File {
        val file = resolve(relativePath)
        file.parentFile?.mkdirs()
        file.writeBytes(ByteArray(size) { 1 })
        return file
    }
}
