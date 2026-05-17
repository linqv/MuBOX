package com.example.comicdav.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class CacheAnalysisTest {
    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun analyzesKnownComicCacheDirectories() {
        temp.root.resolve("remote-comics/book.cbz").writeBytesWithParents(ByteArray(10))
        temp.root.resolve("comicdav-pages/book/page-1.img").writeBytesWithParents(ByteArray(20))
        temp.root.resolve("local-comics/local.cbz").writeBytesWithParents(ByteArray(30))

        val analysis = analyzeComicCache(temp.root)

        assertEquals(60, analysis.totalBytes)
        assertEquals(10, analysis.remoteDownloadsBytes)
        assertEquals(20, analysis.readerPagesBytes)
        assertEquals(30, analysis.localImportsBytes)
    }

    @Test
    fun clearsKnownComicCacheDirectories() {
        val remote = temp.root.resolve("remote-comics/book.cbz").apply {
            parentFile!!.mkdirs()
            writeBytes(ByteArray(10))
        }
        val unrelated = temp.root.resolve("other/keep.txt").apply {
            parentFile!!.mkdirs()
            writeText("keep")
        }

        val deleted = clearComicCache(temp.root)

        assertEquals(1, deleted.filesDeleted)
        assertEquals(10, deleted.bytesDeleted)
        assertFalse(remote.exists())
        assertTrue(unrelated.exists())
    }

    @Test
    fun formatsCacheSizesForSettingsUi() {
        assertEquals("0 B", formatCacheSize(0))
        assertEquals("512 B", formatCacheSize(512))
        assertEquals("1.5 MB", formatCacheSize(1_572_864))
        assertEquals("2.0 GB", formatCacheSize(2_147_483_648))
    }
}

private fun java.io.File.writeBytesWithParents(bytes: ByteArray) {
    parentFile!!.mkdirs()
    writeBytes(bytes)
}
