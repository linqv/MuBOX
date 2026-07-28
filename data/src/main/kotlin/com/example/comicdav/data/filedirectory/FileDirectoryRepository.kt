package com.example.comicdav.data.filedirectory

import com.example.comicdav.core.model.source.FileDirectorySource
import com.example.comicdav.core.model.source.FileDirectorySourceType
import com.example.comicdav.core.ports.FileDirectoryCatalog
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class FileDirectoryRepository internal constructor(
    private val dao: FileDirectoryDao,
    private val clock: () -> Long = { System.currentTimeMillis() },
) : FileDirectoryCatalog {
    override fun observeSources(): Flow<List<FileDirectorySource>> =
        dao.observeSources().map { records -> records.map(FileDirectorySourceEntity::toDomain) }

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
