package com.example.comicdav.core.ports

import com.example.comicdav.core.model.videolibrary.VideoLibraryItemWithSources
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
