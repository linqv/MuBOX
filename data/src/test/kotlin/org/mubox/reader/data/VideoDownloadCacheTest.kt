package org.mubox.reader.data

import org.mubox.reader.core.remote.RemoteFileInfo
import org.mubox.reader.core.remote.WebDavClient
import org.mubox.reader.core.remote.WebDavItem
import java.io.File
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class VideoDownloadCacheTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun successWritesTempFileThenFinalFile() = runTest {
        val targetDirectory = temporaryFolder.newFolder("videos")
        val client = RecordingDownloadClient(byteArrayOf(1, 2, 3, 4))
        val cache = VideoDownloadCache(targetDirectory)

        val result = cache.downloadWebDavVideo(
            client = client,
            remotePath = "/remote/movie.mp4",
            fileName = "bad/name:movie.mp4",
            expectedSize = 4L,
        )

        assertEquals("/remote/movie.mp4", client.requestedPath)
        assertTrue(client.targetFile!!.name.endsWith(".tmp"))
        assertFalse(client.targetFile!!.exists())
        assertEquals("bad_name_movie.mp4", result.file.name)
        assertEquals(byteArrayOf(1, 2, 3, 4).toList(), result.file.readBytes().toList())
        assertEquals(result.file.toURI().toString(), result.localUri)
        assertEquals(4L, result.sizeBytes)
    }

    @Test
    fun failureDeletesTempAndDoesNotLeaveFinalFile() = runTest {
        val targetDirectory = temporaryFolder.newFolder("videos")
        val client = FailingDownloadClient()
        val cache = VideoDownloadCache(targetDirectory)

        val error = runCatching {
            cache.downloadWebDavVideo(
                client = client,
                remotePath = "/remote/movie.mp4",
                fileName = "movie.mp4",
                expectedSize = 4L,
            )
        }.exceptionOrNull()

        assertTrue(error is IllegalStateException)
        assertFalse(targetDirectory.resolve("movie.mp4.tmp").exists())
        assertFalse(targetDirectory.resolve("movie.mp4").exists())
    }

    @Test
    fun knownExpectedSizeMismatchFailsAndDeletesTemp() = runTest {
        val targetDirectory = temporaryFolder.newFolder("videos")
        val client = RecordingDownloadClient(byteArrayOf(1, 2, 3, 4))
        val cache = VideoDownloadCache(targetDirectory)

        val error = runCatching {
            cache.downloadWebDavVideo(
                client = client,
                remotePath = "/remote/movie.mp4",
                fileName = "movie.mp4",
                expectedSize = 8L,
            )
        }.exceptionOrNull()

        assertTrue(error is IllegalStateException)
        assertFalse(targetDirectory.resolve("movie.mp4.tmp").exists())
        assertFalse(targetDirectory.resolve("movie.mp4").exists())
    }

    private class RecordingDownloadClient(
        private val bytes: ByteArray,
    ) : WebDavClient {
        var requestedPath: String? = null
            private set
        var targetFile: File? = null
            private set

        override suspend fun list(path: String): List<WebDavItem> = emptyList()

        override suspend fun head(path: String): RemoteFileInfo =
            RemoteFileInfo(path, bytes.size.toLong(), etag = null, lastModified = null, supportsRange = true)

        override suspend fun readRange(path: String, start: Long, endInclusive: Long): ByteArray =
            error("readRange is not used by VideoDownloadCache")

        override suspend fun download(path: String, target: File, onBytesRead: (Long) -> Unit): Long {
            requestedPath = path
            targetFile = target
            target.writeBytes(bytes)
            onBytesRead(bytes.size.toLong())
            return bytes.size.toLong()
        }
    }

    private class FailingDownloadClient : WebDavClient {
        override suspend fun list(path: String): List<WebDavItem> = emptyList()

        override suspend fun head(path: String): RemoteFileInfo =
            RemoteFileInfo(path, 4L, etag = null, lastModified = null, supportsRange = true)

        override suspend fun readRange(path: String, start: Long, endInclusive: Long): ByteArray =
            error("readRange is not used by VideoDownloadCache")

        override suspend fun download(path: String, target: File, onBytesRead: (Long) -> Unit): Long {
            target.writeText("partial")
            onBytesRead(target.length())
            throw IllegalStateException("network failed")
        }
    }
}
