package com.example.comicdav.data.library

import com.example.comicdav.core.model.library.LibraryItemWithSources
import com.example.comicdav.core.model.library.SourceType
import com.example.comicdav.core.model.media.localComicTitleFromFileName
import com.example.comicdav.core.ports.LibraryCatalog
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class LibraryRepository internal constructor(
    private val dao: LibraryDao,
    private val clock: () -> Long = { System.currentTimeMillis() },
) : LibraryCatalog {
    override suspend fun addLocalComic(
        uri: String,
        fileName: String,
        size: Long?,
        lastModified: Long?,
    ): Long {
        dao.findLocalComicId(uri)?.let { return it }
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
        coverPath: String?,
    ): Long {
        dao.findWebDavComicId(accountId, remotePath)?.let { return it }
        val title = titleFrom(fileName)
        return dao.insertWebDavComic(
            item = LibraryItemEntity(
                title = title,
                displayName = title,
                sourceType = SourceType.WEBDAV,
                coverPath = coverPath,
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
        return dao.observeLibrary().map { records -> records.map(LibraryItemRelation::toDomain) }
    }

    override suspend fun markOpened(libraryItemId: Long) {
        dao.updateLastOpened(libraryItemId, clock())
    }

    override suspend fun updateCoverPath(libraryItemId: Long, coverPath: String?) {
        dao.updateCoverPath(libraryItemId, coverPath)
    }

    override suspend fun removeComic(libraryItemId: Long) {
        dao.deleteLibraryItem(libraryItemId)
    }

    private fun titleFrom(fileName: String): String {
        return localComicTitleFromFileName(fileName)
    }
}
