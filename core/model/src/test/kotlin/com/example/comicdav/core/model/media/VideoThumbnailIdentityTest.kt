package com.example.comicdav.core.model.media

import com.example.comicdav.core.remote.WebDavItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class VideoThumbnailIdentityTest {
    @Test
    fun localIdentityTracksFileMetadata() {
        val entry = MediaEntry(
            name = "movie.mp4",
            uri = "content://videos/movie.mp4",
            isDirectory = false,
            size = 100L,
            lastModified = 200L,
        )

        assertNotEquals(
            fileDirectoryVideoThumbnailVersion(entry),
            fileDirectoryVideoThumbnailVersion(entry.copy(size = 101L)),
        )
        assertNotEquals(
            fileDirectoryVideoThumbnailVersion(entry),
            fileDirectoryVideoThumbnailVersion(entry.copy(lastModified = 201L)),
        )
    }

    @Test
    fun localBrowserRevisionOnlyCompensatesForMissingTimestamp() {
        val unknownTimestamp = MediaEntry(
            name = "movie.mp4",
            uri = "content://videos/movie.mp4",
            isDirectory = false,
            size = 100L,
        )

        assertNotEquals(
            fileDirectoryBrowserVideoThumbnailVersion(unknownTimestamp, requestRevision = 1L),
            fileDirectoryBrowserVideoThumbnailVersion(unknownTimestamp, requestRevision = 2L),
        )
        val timestamped = unknownTimestamp.copy(lastModified = 200L)
        assertEquals(
            fileDirectoryBrowserVideoThumbnailVersion(timestamped, requestRevision = 1L),
            fileDirectoryBrowserVideoThumbnailVersion(timestamped, requestRevision = 2L),
        )
    }

    @Test
    fun webDavBrowserRevisionOnlyCompensatesForMissingValidators() {
        val unknownVersion = WebDavItem(
            name = "movie.mp4",
            path = "/movie.mp4",
            isDirectory = false,
            size = 100L,
            etag = null,
            lastModified = null,
        )

        assertNotEquals(
            webDavBrowserVideoThumbnailVersion(unknownVersion, requestRevision = 1L),
            webDavBrowserVideoThumbnailVersion(unknownVersion, requestRevision = 2L),
        )
        val validated = unknownVersion.copy(etag = "v1")
        assertEquals(
            webDavBrowserVideoThumbnailVersion(validated, requestRevision = 1L),
            webDavBrowserVideoThumbnailVersion(validated, requestRevision = 2L),
        )
    }
}
