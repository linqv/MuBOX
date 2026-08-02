package org.mubox.reader.core.ports

import org.mubox.reader.core.model.videolibrary.VideoLibraryItemWithSources
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
    suspend fun synchronizeLocalVideoThumbnail(
        videoLibraryItemId: Long,
        fileName: String,
        size: Long?,
        lastModified: Long?,
        thumbnailPath: String,
    )
    suspend fun synchronizeWebDavVideoThumbnail(
        videoLibraryItemId: Long,
        fileName: String,
        size: Long?,
        etag: String?,
        lastModified: Long?,
        thumbnailPath: String,
    )
    suspend fun updateThumbnailPath(videoLibraryItemId: Long, thumbnailPath: String?)
    suspend fun removeVideo(videoLibraryItemId: Long)
}
