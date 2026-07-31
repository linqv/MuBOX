package com.example.comicdav

import com.example.comicdav.core.model.media.MediaEntry
import com.example.comicdav.core.model.videolibrary.VideoLibraryItem
import com.example.comicdav.core.model.videolibrary.VideoLibraryItemWithSources
import com.example.comicdav.core.model.videolibrary.VideoSourceType
import com.example.comicdav.core.ports.VideoLibraryCatalog
import com.example.comicdav.core.remote.WebDavItem
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class AppVideoLibraryCoordinatorTest {
    private val catalog = RecordingVideoLibraryCatalog()
    private val coordinator = AppVideoLibraryCoordinator(catalog)

    @Test
    fun localItemFieldsAreDelegatedToTheCatalog() = runTest {
        coordinator.addLocal(
            item = MediaEntry(
                name = "episode-01.mkv",
                uri = "content://videos/episode-01.mkv",
                isDirectory = false,
                size = 42L,
                lastModified = 100L,
            ),
            thumbnailPath = "/cache/episode-01.jpg",
        )

        assertEquals(
            LocalAdd(
                uri = "content://videos/episode-01.mkv",
                fileName = "episode-01.mkv",
                size = 42L,
                lastModified = 100L,
                thumbnailPath = "/cache/episode-01.jpg",
            ),
            catalog.localAdd,
        )
    }

    @Test
    fun webDavItemFieldsAreDelegatedToTheCatalog() = runTest {
        coordinator.addWebDav(
            accountId = "https://dav.example|reader",
            item = WebDavItem(
                path = "/shows/episode-02.mp4",
                name = "episode-02.mp4",
                isDirectory = false,
                size = 84L,
                etag = "etag-02",
                lastModified = 200L,
            ),
            thumbnailPath = "/cache/episode-02.jpg",
        )

        assertEquals(
            WebDavAdd(
                accountId = "https://dav.example|reader",
                remotePath = "/shows/episode-02.mp4",
                fileName = "episode-02.mp4",
                size = 84L,
                etag = "etag-02",
                lastModified = 200L,
                thumbnailPath = "/cache/episode-02.jpg",
            ),
            catalog.webDavAdd,
        )
    }

    @Test
    fun mutationsUseTheVideoLibraryItemId() = runTest {
        val item = VideoLibraryItemWithSources(
            item = VideoLibraryItem(
                id = 17L,
                title = "episode",
                displayName = "episode",
                sourceType = VideoSourceType.LOCAL,
                addedAt = 1L,
            ),
            localSource = null,
            webDavSource = null,
        )

        coordinator.updateThumbnail(item, "/cache/new.jpg")
        coordinator.remove(item)

        assertEquals(17L to "/cache/new.jpg", catalog.thumbnailUpdate)
        assertEquals(17L, catalog.removedId)
    }
}

private data class LocalAdd(
    val uri: String,
    val fileName: String,
    val size: Long?,
    val lastModified: Long?,
    val thumbnailPath: String?,
)

private data class WebDavAdd(
    val accountId: String,
    val remotePath: String,
    val fileName: String,
    val size: Long?,
    val etag: String?,
    val lastModified: Long?,
    val thumbnailPath: String?,
)

private class RecordingVideoLibraryCatalog : VideoLibraryCatalog {
    var localAdd: LocalAdd? = null
    var webDavAdd: WebDavAdd? = null
    var thumbnailUpdate: Pair<Long, String?>? = null
    var removedId: Long? = null

    override fun observeVideoLibrary(): Flow<List<VideoLibraryItemWithSources>> = emptyFlow()

    override suspend fun addLocalVideo(
        uri: String,
        fileName: String,
        size: Long?,
        lastModified: Long?,
        thumbnailPath: String?,
    ): Long {
        localAdd = LocalAdd(uri, fileName, size, lastModified, thumbnailPath)
        return 1L
    }

    override suspend fun addWebDavVideo(
        accountId: String,
        remotePath: String,
        fileName: String,
        size: Long?,
        etag: String?,
        lastModified: Long?,
        thumbnailPath: String?,
    ): Long {
        webDavAdd = WebDavAdd(
            accountId,
            remotePath,
            fileName,
            size,
            etag,
            lastModified,
            thumbnailPath,
        )
        return 2L
    }

    override suspend fun markOpened(videoLibraryItemId: Long) = Unit

    override suspend fun updateThumbnailPath(
        videoLibraryItemId: Long,
        thumbnailPath: String?,
    ) {
        thumbnailUpdate = videoLibraryItemId to thumbnailPath
    }

    override suspend fun removeVideo(videoLibraryItemId: Long) {
        removedId = videoLibraryItemId
    }
}
