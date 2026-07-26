package com.example.comicdav.feature.videolibrary

import android.graphics.Bitmap
import java.io.File
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
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

    @Test
    fun customCacheSubdirectorySeparatesHistoryThumbnails() = runTest {
        val cacheDir = temporaryFolder.newFolder("history-cache")
        val frame = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
        val extractor = VideoThumbnailExtractor(
            cacheDir = cacheDir,
            frameProvider = { frame },
            cacheSubdirectory = "history-thumbnails",
        )

        val result = extractor.extractFromFile(
            file = File("/tmp/history.mp4"),
            stableKey = "history-key",
        )

        assertTrue(File(requireNotNull(result)).isFile)
        assertTrue(result.contains("/history-thumbnails/"))
        assertFalse(cacheDir.resolve("video-library-thumbnails").exists())
        frame.recycle()
    }
}
