package com.example.comicdav.feature.videolibrary

import java.io.File
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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
}
