package com.example.comicdav.data.videolibrary

import com.example.comicdav.core.model.videolibrary.VideoLibraryItemWithSources
import com.example.comicdav.core.model.videolibrary.VideoSourceType
import com.example.comicdav.core.ports.VideoLibraryCatalog
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class VideoLibraryRepository internal constructor(
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
        dao.findLocalVideoId(uri)?.let { existingId ->
            dao.synchronizeLocalVideoThumbnail(
                videoLibraryItemId = existingId,
                fileName = fileName,
                size = size,
                lastModified = lastModified,
                thumbnailPath = thumbnailPath,
            )
            return existingId
        }
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
        dao.findWebDavVideoId(accountId, remotePath)?.let { existingId ->
            dao.synchronizeWebDavVideoThumbnail(
                videoLibraryItemId = existingId,
                fileName = fileName,
                size = size,
                etag = etag,
                lastModified = lastModified,
                thumbnailPath = thumbnailPath,
            )
            return existingId
        }
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
        return dao.observeVideoLibrary().map { records -> records.map(VideoLibraryItemRelation::toDomain) }
    }

    override suspend fun markOpened(videoLibraryItemId: Long) {
        dao.updateLastOpened(videoLibraryItemId, clock())
    }

    override suspend fun synchronizeLocalVideoThumbnail(
        videoLibraryItemId: Long,
        fileName: String,
        size: Long?,
        lastModified: Long?,
        thumbnailPath: String,
    ) {
        dao.synchronizeLocalVideoThumbnail(
            videoLibraryItemId = videoLibraryItemId,
            fileName = fileName,
            size = size,
            lastModified = lastModified,
            thumbnailPath = thumbnailPath,
        )
    }

    override suspend fun synchronizeWebDavVideoThumbnail(
        videoLibraryItemId: Long,
        fileName: String,
        size: Long?,
        etag: String?,
        lastModified: Long?,
        thumbnailPath: String,
    ) {
        dao.synchronizeWebDavVideoThumbnail(
            videoLibraryItemId = videoLibraryItemId,
            fileName = fileName,
            size = size,
            etag = etag,
            lastModified = lastModified,
            thumbnailPath = thumbnailPath,
        )
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
