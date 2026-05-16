package com.example.comicdav.data.library

import kotlinx.coroutines.flow.Flow
import java.util.Locale

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
    ): Long

    suspend fun markOpened(libraryItemId: Long)
}

class LibraryRepository(
    private val dao: LibraryDao,
    private val clock: () -> Long = { System.currentTimeMillis() },
) : LibraryCatalog {
    override suspend fun addLocalComic(
        uri: String,
        fileName: String,
        size: Long?,
        lastModified: Long?,
    ): Long {
        val title = titleFrom(fileName)
        return dao.insertLocalComic(
            item = LibraryItemEntity(
                title = title,
                displayName = title,
                sourceType = SourceType.LOCAL,
                addedAt = clock(),
            ),
            source = LocalComicSourceEntity(
                libraryItemId = 0L,
                uri = uri,
                fileName = fileName,
                size = size,
                lastModified = lastModified,
            ),
        )
    }

    override suspend fun addWebDavComic(
        accountId: String,
        remotePath: String,
        fileName: String,
        size: Long?,
        etag: String?,
        lastModified: Long?,
        cacheKey: String?,
    ): Long {
        val title = titleFrom(fileName)
        return dao.insertWebDavComic(
            item = LibraryItemEntity(
                title = title,
                displayName = title,
                sourceType = SourceType.WEBDAV,
                addedAt = clock(),
            ),
            source = WebDavComicSourceEntity(
                libraryItemId = 0L,
                accountId = accountId,
                remotePath = remotePath,
                fileName = fileName,
                size = size,
                etag = etag,
                lastModified = lastModified,
                cacheKey = cacheKey,
            ),
        )
    }

    override fun observeLibrary(): Flow<List<LibraryItemWithSources>> {
        return dao.observeLibrary()
    }

    override suspend fun markOpened(libraryItemId: Long) {
        dao.updateLastOpened(libraryItemId, clock())
    }

    private fun titleFrom(fileName: String): String {
        val lowerCaseFileName = fileName.lowercase(Locale.ROOT)
        return when {
            lowerCaseFileName.endsWith(".cbz") -> fileName.dropLast(".cbz".length)
            lowerCaseFileName.endsWith(".zip") -> fileName.dropLast(".zip".length)
            else -> fileName
        }
    }
}
