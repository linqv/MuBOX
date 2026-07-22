package com.example.comicdav.data.filedirectory

import kotlinx.coroutines.flow.Flow

interface FileDirectoryCatalog {
    fun observeSources(): Flow<List<FileDirectorySourceEntity>>

    suspend fun addLocalDirectory(displayName: String, treeUri: String): Long

    suspend fun addWebDavDirectory(displayName: String, accountId: String, path: String): Long

    suspend fun deleteSource(id: Long) = Unit

    suspend fun updateWebDavDirectory(
        id: Long,
        displayName: String,
        accountId: String,
        path: String,
    ) = Unit
}

class FileDirectoryRepository(
    private val dao: FileDirectoryDao,
    private val clock: () -> Long = { System.currentTimeMillis() },
) : FileDirectoryCatalog {
    override fun observeSources(): Flow<List<FileDirectorySourceEntity>> = dao.observeSources()

    override suspend fun addLocalDirectory(displayName: String, treeUri: String): Long {
        return dao.insertSource(
            FileDirectorySourceEntity(
                displayName = displayName,
                sourceType = FileDirectorySourceType.LOCAL,
                localTreeUri = treeUri,
                addedAt = clock(),
            ),
        )
    }

    override suspend fun addWebDavDirectory(displayName: String, accountId: String, path: String): Long {
        return dao.insertSource(
            FileDirectorySourceEntity(
                displayName = displayName,
                sourceType = FileDirectorySourceType.WEBDAV,
                webDavAccountId = accountId,
                webDavPath = path,
                addedAt = clock(),
            ),
        )
    }

    override suspend fun deleteSource(id: Long) {
        dao.deleteSource(id)
    }

    override suspend fun updateWebDavDirectory(
        id: Long,
        displayName: String,
        accountId: String,
        path: String,
    ) {
        dao.updateWebDavSource(
            id = id,
            displayName = displayName,
            accountId = accountId,
            path = path,
        )
    }
}
