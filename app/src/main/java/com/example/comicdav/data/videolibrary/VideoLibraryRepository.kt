package com.example.comicdav.data.videolibrary

import kotlinx.coroutines.flow.Flow

interface VideoLibraryCatalog {
    fun observeVideoLibrary(): Flow<List<VideoLibraryItemWithSources>>

    suspend fun addLocalVideo(
        uri: String,
        fileName: String,
        size: Long? = null,
        lastModified: Long? = null,
        thumbnailPath: String? = null,
    ): Long

    suspend fun addWebDavVideo(
        accountId: String,
        remotePath: String,
        fileName: String,
        size: Long? = null,
        etag: String? = null,
        lastModified: Long? = null,
        thumbnailPath: String? = null,
    ): Long

    suspend fun markOpened(videoLibraryItemId: Long)

    suspend fun updateThumbnailPath(videoLibraryItemId: Long, thumbnailPath: String?)

    suspend fun removeVideo(videoLibraryItemId: Long)
}

class VideoLibraryRepository(
    private val dao: VideoLibraryDao,
    private val clock: () -> Long = { System.currentTimeMillis() },
) : VideoLibraryCatalog {
    override suspend fun addLocalVideo(
        uri: String,
        fileName: String,
        size: Long?,
        lastModified: Long?,
        thumbnailPath: String?,
    ): Long {
        dao.findLocalVideoId(uri)?.let { return it }
        val title = titleFrom(fileName)
        return dao.insertLocalVideo(
            item = VideoLibraryItemEntity(
                title = title,
                displayName = title,
                sourceType = VideoSourceType.LOCAL,
                thumbnailPath = thumbnailPath,
                addedAt = clock(),
            ),
            source = LocalVideoSourceEntity(
                videoLibraryItemId = 0L,
                uri = uri,
                fileName = fileName,
                size = size,
                lastModified = lastModified,
            ),
        )
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
        dao.findWebDavVideoId(accountId, remotePath)?.let { return it }
        val title = titleFrom(fileName)
        return dao.insertWebDavVideo(
            item = VideoLibraryItemEntity(
                title = title,
                displayName = title,
                sourceType = VideoSourceType.WEBDAV,
                thumbnailPath = thumbnailPath,
                addedAt = clock(),
            ),
            source = WebDavVideoSourceEntity(
                videoLibraryItemId = 0L,
                accountId = accountId,
                remotePath = remotePath,
                fileName = fileName,
                size = size,
                etag = etag,
                lastModified = lastModified,
            ),
        )
    }

    override fun observeVideoLibrary(): Flow<List<VideoLibraryItemWithSources>> {
        return dao.observeVideoLibrary()
    }

    override suspend fun markOpened(videoLibraryItemId: Long) {
        dao.updateLastOpened(videoLibraryItemId, clock())
    }

    override suspend fun updateThumbnailPath(videoLibraryItemId: Long, thumbnailPath: String?) {
        dao.updateThumbnailPath(videoLibraryItemId, thumbnailPath)
    }

    override suspend fun removeVideo(videoLibraryItemId: Long) {
        dao.deleteVideoLibraryItem(videoLibraryItemId)
    }

    private fun titleFrom(fileName: String): String {
        val withoutExtension = fileName.substringBeforeLast('.', missingDelimiterValue = fileName)
        return withoutExtension.ifBlank { fileName }
    }
}
