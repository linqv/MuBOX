package com.example.comicdav.feature.videolibrary

import java.io.File
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class VideoThumbnailExtractorTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun extractFromFileReturnsNullWhenRetrieverThrows() = runTest {
        val cacheDir = temporaryFolder.newFolder("cache")
        val extractor = VideoThumbnailExtractor(
            cacheDir = cacheDir,
            frameProvider = { throw IllegalStateException("cannot decode") },
        )

        val result = extractor.extractFromFile(File("/tmp/movie.mp4"))

        assertNull(result)
        assertFalse(cacheDir.resolve("video-library-thumbnails").exists())
    }

    @Test
    fun thumbnailFileNameUsesFullStableKeyHashToAvoidPrefixCollisions() {
        val commonPrefix = "webdav:account:/very/long/path/".padEnd(140, 'a')

        val first = thumbnailFileNameForStableKey("${commonPrefix}1")
        val second = thumbnailFileNameForStableKey("${commonPrefix}2")

        assertNotEquals(first, second)
        assertTrue(first.endsWith(".jpg"))
        assertTrue(second.endsWith(".jpg"))
    }
}
