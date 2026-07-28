package com.example.comicdav.core.ports

import com.example.comicdav.core.model.library.LibraryItemWithSources
import kotlinx.coroutines.flow.Flow

interface LibraryCatalog {
    fun observeLibrary(): Flow<List<LibraryItemWithSources>>

    suspend fun addLocalComic(
        uri: String,
        fileName: String,
        size: Long? = null,
        lastModified: Long? = null,
    ): Long

    suspend fun addWebDavComic(
        accountId: String,
        remotePath: String,
        fileName: String,
        size: Long? = null,
        etag: String? = null,
        lastModified: Long? = null,
        cacheKey: String? = null,
        coverPath: String? = null,
    ): Long

    suspend fun markOpened(libraryItemId: Long)
    suspend fun updateCoverPath(libraryItemId: Long, coverPath: String?)
    suspend fun removeComic(libraryItemId: Long)
}
