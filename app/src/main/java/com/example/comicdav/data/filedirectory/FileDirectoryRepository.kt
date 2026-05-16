package com.example.comicdav.data.filedirectory

import kotlinx.coroutines.flow.Flow

interface FileDirectoryCatalog {
    fun observeSources(): Flow<List<FileDirectorySourceEntity>>

    suspend fun addLocalDirectory(displayName: String, treeUri: String): Long

    suspend fun addWebDavDirectory(displayName: String, accountId: String, path: String): Long

    suspend fun addWebDavDirectory(
        displayName: String,
        accountId: String,
        path: String,
        baseUrl: String,
        username: String,
        password: String,
    ): Long {
        return addWebDavDirectory(displayName, accountId, path)
    }

    suspend fun deleteSource(id: Long) = Unit
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
        return addWebDavDirectory(
            displayName = displayName,
            accountId = accountId,
            path = path,
            baseUrl = "",
            username = "",
            password = "",
        )
    }

    override suspend fun addWebDavDirectory(
        displayName: String,
        accountId: String,
        path: String,
        baseUrl: String,
        username: String,
        password: String,
    ): Long {
        return dao.insertSource(
            FileDirectorySourceEntity(
                displayName = displayName,
                sourceType = FileDirectorySourceType.WEBDAV,
                webDavAccountId = accountId,
                webDavPath = path,
                webDavBaseUrl = baseUrl.ifEmpty { null },
                webDavUsername = username.ifEmpty { null },
                webDavPassword = password.ifEmpty { null },
                addedAt = clock(),
            ),
        )
    }

    override suspend fun deleteSource(id: Long) {
        dao.deleteSource(id)
    }
}
