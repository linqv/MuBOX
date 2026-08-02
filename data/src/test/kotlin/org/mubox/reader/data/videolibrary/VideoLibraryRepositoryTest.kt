package org.mubox.reader.data.videolibrary

import org.mubox.reader.core.model.videolibrary.LocalVideoSource
import org.mubox.reader.core.model.videolibrary.VideoLibraryItem
import org.mubox.reader.core.model.videolibrary.VideoSourceType
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class VideoLibraryRepositoryTest {
    @Test
    fun observeVideoLibraryMapsPersistenceRecordsToDomainModels() = runTest {
        val dao = FakeVideoLibraryDao()
        val repository = VideoLibraryRepository(dao)
        dao.emit(
            VideoLibraryItemRelation(
                item = VideoLibraryItemEntity(
                    id = 7L,
                    title = "Movie",
                    displayName = "Movie.mp4",
                    sourceType = VideoSourceType.LOCAL,
                    thumbnailPath = "/thumbnails/movie.jpg",
                    addedAt = 10L,
                    lastOpenedAt = 20L,
                ),
                localSource = LocalVideoSourceEntity(
                    videoLibraryItemId = 7L,
                    uri = "content://video/7",
                    fileName = "Movie.mp4",
                    size = 100L,
                    lastModified = 30L,
                ),
                webDavSource = null,
            ),
        )

        val result = repository.observeVideoLibrary().first().single()

        assertEquals(VideoLibraryItem::class, result.item::class)
        assertEquals(7L, result.item.id)
        assertEquals("/thumbnails/movie.jpg", result.item.thumbnailPath)
        val localSource = checkNotNull(result.localSource)
        assertEquals(LocalVideoSource::class, localSource::class)
        assertEquals("content://video/7", localSource.uri)
    }

    @Test
    fun addLocalVideoReturnsExistingIdForSameUri() = runTest {
        val dao = FakeVideoLibraryDao()
        val repository = VideoLibraryRepository(dao, clock = { 10L })

        val first = repository.addLocalVideo("content://video/1", "movie.mp4", 100L, 20L)
        val second = repository.addLocalVideo(
            "content://video/1",
            "renamed-movie.mp4",
            120L,
            30L,
            "/cache/shared.jpg",
        )

        assertEquals(first, second)
        assertEquals(1, dao.insertedItems)
        assertEquals(listOf(first to "/cache/shared.jpg"), dao.updatedThumbnailPaths)
        assertEquals(
            listOf(LocalMetadataUpdate(first, "renamed-movie.mp4", 120L, 30L)),
            dao.updatedLocalMetadata,
        )
    }

    @Test
    fun addWebDavVideoReturnsExistingIdForSameAccountAndPath() = runTest {
        val dao = FakeVideoLibraryDao()
        val repository = VideoLibraryRepository(dao, clock = { 10L })

        val first = repository.addWebDavVideo("account", "/encoded/movie.mp4", "movie.mp4", 100L, "etag", 20L)
        val second = repository.addWebDavVideo(
            "account",
            "/encoded/movie.mp4",
            "renamed-movie.mp4",
            120L,
            "etag-v2",
            30L,
            "/cache/shared.jpg",
        )

        assertEquals(first, second)
        assertEquals(1, dao.insertedItems)
        assertEquals(listOf(first to "/cache/shared.jpg"), dao.updatedThumbnailPaths)
        assertEquals(
            listOf(WebDavMetadataUpdate(first, "renamed-movie.mp4", 120L, "etag-v2", 30L)),
            dao.updatedWebDavMetadata,
        )
    }

    @Test
    fun updateThumbnailPathDelegatesToDao() = runTest {
        val dao = FakeVideoLibraryDao()
        val repository = VideoLibraryRepository(dao, clock = { 10L })

        repository.updateThumbnailPath(42L, "/covers/video.jpg")

        assertEquals(42L to "/covers/video.jpg", dao.updatedThumbnailPaths.single())
    }

    @Test
    fun removeVideoDeletesLibraryItem() = runTest {
        val dao = FakeVideoLibraryDao()
        val repository = VideoLibraryRepository(dao, clock = { 10L })

        repository.removeVideo(42L)

        assertEquals(listOf(42L), dao.deletedItemIds)
    }
}

private class FakeVideoLibraryDao : VideoLibraryDao() {
    var insertedItems: Int = 0
        private set
    val updatedThumbnailPaths = mutableListOf<Pair<Long, String?>>()
    val updatedLocalMetadata = mutableListOf<LocalMetadataUpdate>()
    val updatedWebDavMetadata = mutableListOf<WebDavMetadataUpdate>()
    val deletedItemIds = mutableListOf<Long>()
    private val items = MutableStateFlow<List<VideoLibraryItemRelation>>(emptyList())
    private val localIdsByUri = mutableMapOf<String, Long>()
    private val webDavIdsByAccountAndPath = mutableMapOf<Pair<String, String>, Long>()
    private var nextId = 1L

    override suspend fun insertVideoLibraryItem(item: VideoLibraryItemEntity): Long {
        insertedItems += 1
        return nextId++
    }

    override suspend fun insertLocalVideoSource(source: LocalVideoSourceEntity) {
        localIdsByUri[source.uri] = source.videoLibraryItemId
    }

    override suspend fun insertWebDavVideoSource(source: WebDavVideoSourceEntity) {
        webDavIdsByAccountAndPath[source.accountId to source.remotePath] = source.videoLibraryItemId
    }

    override fun observeVideoLibrary(): Flow<List<VideoLibraryItemRelation>> = items

    override suspend fun getVideoLibrary(): List<VideoLibraryItemRelation> = items.value

    fun emit(item: VideoLibraryItemRelation) {
        items.value = listOf(item)
    }

    override suspend fun findLocalVideoId(uri: String): Long? = localIdsByUri[uri]

    override suspend fun findWebDavVideoId(accountId: String, remotePath: String): Long? =
        webDavIdsByAccountAndPath[accountId to remotePath]

    override suspend fun updateLastOpened(videoLibraryItemId: Long, openedAt: Long) = Unit

    override suspend fun updateThumbnailPath(videoLibraryItemId: Long, thumbnailPath: String?) {
        updatedThumbnailPaths += videoLibraryItemId to thumbnailPath
    }

    override suspend fun updateLocalVideoSourceMetadata(
        videoLibraryItemId: Long,
        fileName: String,
        size: Long?,
        lastModified: Long?,
    ) {
        updatedLocalMetadata += LocalMetadataUpdate(videoLibraryItemId, fileName, size, lastModified)
    }

    override suspend fun updateWebDavVideoSourceMetadata(
        videoLibraryItemId: Long,
        fileName: String,
        size: Long?,
        etag: String?,
        lastModified: Long?,
    ) {
        updatedWebDavMetadata += WebDavMetadataUpdate(
            videoLibraryItemId,
            fileName,
            size,
            etag,
            lastModified,
        )
    }

    override suspend fun deleteVideoLibraryItem(videoLibraryItemId: Long) {
        deletedItemIds += videoLibraryItemId
    }
}

private data class LocalMetadataUpdate(
    val videoLibraryItemId: Long,
    val fileName: String,
    val size: Long?,
    val lastModified: Long?,
)

private data class WebDavMetadataUpdate(
    val videoLibraryItemId: Long,
    val fileName: String,
    val size: Long?,
    val etag: String?,
    val lastModified: Long?,
)
