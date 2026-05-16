package com.example.comicdav.data.filedirectory

import kotlinx.coroutines.flow.Flow

interface FileDirectoryCatalog {
    fun observeSources(): Flow<List<FileDirectorySourceEntity>>

    suspend fun addLocalDirectory(displayName: String, treeUri: String): Long

    suspend fun addWebDavDirectory(displayName: String, accountId: String, path: String): Long
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
}
