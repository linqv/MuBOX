package com.example.comicdav.feature.videolibrary

import android.graphics.Bitmap
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
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
    fun existingThumbnailIsReusedWithoutDecodingVideoAgain() = runTest {
        val cacheDir = temporaryFolder.newFolder("cached-thumbnail")
        val stableKey = "local:movie"
        val thumbnailFile = cacheDir
            .resolve("video-library-thumbnails")
            .resolve(thumbnailFileNameForStableKey(stableKey))
            .apply {
                parentFile?.mkdirs()
                writeText("thumbnail")
            }
        var frameRequests = 0
        val extractor = VideoThumbnailExtractor(
            cacheDir = cacheDir,
            frameProvider = {
                frameRequests += 1
                null
            },
        )

        val result = extractor.extractFromFile(
            file = File("/tmp/movie.mp4"),
            stableKey = stableKey,
        )

        assertEquals(thumbnailFile.absolutePath, result)
        assertEquals(0, frameRequests)
    }

    @Test
    fun cachedThumbnailPathCanBeCheckedBeforeOpeningTheMediaSource() = runTest {
        val cacheDir = temporaryFolder.newFolder("cached-thumbnail-preflight")
        val stableKey = "webdav:account:/movie.mp4"
        val thumbnailFile = cacheDir
            .resolve("video-library-thumbnails")
            .resolve(thumbnailFileNameForStableKey(stableKey))
            .apply {
                parentFile?.mkdirs()
                writeText("thumbnail")
            }
        val extractor = VideoThumbnailExtractor(
            cacheDir = cacheDir,
            frameProvider = { error("media source must not be opened for a cache preflight") },
        )

        assertEquals(
            thumbnailFile.absolutePath,
            extractor.cachedThumbnailPath(stableKey),
        )
        assertNull(extractor.cachedThumbnailPath("$stableKey:missing"))
    }

    @Test
    fun forceRefreshDecodesVideoEvenWhenThumbnailIsCached() = runTest {
        val cacheDir = temporaryFolder.newFolder("force-refresh-thumbnail")
        val stableKey = "local:movie"
        cacheDir
            .resolve("video-library-thumbnails")
            .resolve(thumbnailFileNameForStableKey(stableKey))
            .apply {
                parentFile?.mkdirs()
                writeText("old thumbnail")
            }
        val frame = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
        var frameRequests = 0
        val extractor = VideoThumbnailExtractor(
            cacheDir = cacheDir,
            frameProvider = {
                frameRequests += 1
                frame
            },
        )

        val result = extractor.extractFromFile(
            file = File("/tmp/movie.mp4"),
            stableKey = stableKey,
            forceRefresh = true,
        )

        assertTrue(File(requireNotNull(result)).length() > 0L)
        assertEquals(1, frameRequests)
        frame.recycle()
    }

    @Test
    fun boundedBrowserCachePrunesOldFilesWithoutTouchingLibraryThumbnails() = runTest {
        val cacheDir = temporaryFolder.newFolder("bounded-browser-thumbnails")
        val browserSubdirectory = "video-library-thumbnails/browser"
        val browserDir = cacheDir.resolve(browserSubdirectory).apply { mkdirs() }
        val oldFile = browserDir
            .resolve(thumbnailFileNameForStableKey("browser:old"))
            .apply {
                writeBytes(ByteArray(8))
                setLastModified(1_000L)
            }
        val currentFile = browserDir
            .resolve(thumbnailFileNameForStableKey("browser:current"))
            .apply {
                writeBytes(ByteArray(9))
                setLastModified(2_000L)
            }
        val libraryFile = cacheDir
            .resolve("video-library-thumbnails/library.jpg")
            .apply {
                parentFile?.mkdirs()
                writeBytes(ByteArray(8))
            }
        val extractor = VideoThumbnailExtractor(
            cacheDir = cacheDir,
            frameProvider = { error("cached thumbnail should not be decoded") },
            cacheSubdirectory = browserSubdirectory,
            maxCacheBytes = 9L,
        )

        assertEquals(
            currentFile.absolutePath,
            extractor.cachedThumbnailPath("browser:current"),
        )
        assertFalse(oldFile.exists())
        assertTrue(currentFile.isFile)
        assertTrue(libraryFile.isFile)
    }

    @Test
    fun concurrentWritesForTheSameStableKeyAreSerialized() = runTest {
        val cacheDir = temporaryFolder.newFolder("concurrent-thumbnail")
        val frame = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
        val firstFrameRequest = CountDownLatch(1)
        val releaseFirstFrameRequest = CountDownLatch(1)
        val secondExtractionStarted = CountDownLatch(1)
        val secondFrameRequest = CountDownLatch(1)
        val frameRequests = AtomicInteger()
        val extractor = VideoThumbnailExtractor(
            cacheDir = cacheDir,
            frameProvider = {
                when (frameRequests.incrementAndGet()) {
                    1 -> {
                        firstFrameRequest.countDown()
                        check(releaseFirstFrameRequest.await(2, TimeUnit.SECONDS))
                    }
                    2 -> secondFrameRequest.countDown()
                }
                frame
            },
            ioDispatcher = Dispatchers.Default,
        )

        val first = async(Dispatchers.Default) {
            extractor.extractFromFile(
                file = File("/tmp/movie.mp4"),
                stableKey = "local:movie",
                forceRefresh = true,
            )
        }
        assertTrue(firstFrameRequest.await(2, TimeUnit.SECONDS))
        val second = async(Dispatchers.Default) {
            secondExtractionStarted.countDown()
            extractor.extractFromFile(
                file = File("/tmp/movie.mp4"),
                stableKey = "local:movie",
                forceRefresh = true,
            )
        }

        assertTrue(secondExtractionStarted.await(2, TimeUnit.SECONDS))
        assertFalse(secondFrameRequest.await(200, TimeUnit.MILLISECONDS))
        releaseFirstFrameRequest.countDown()
        val firstResult = first.await()
        val secondResult = second.await()

        assertEquals(firstResult, secondResult)
        assertTrue(File(requireNotNull(firstResult)).length() > 0L)
        assertEquals(2, frameRequests.get())
        frame.recycle()
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
