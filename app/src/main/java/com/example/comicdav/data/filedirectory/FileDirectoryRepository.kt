package com.example.comicdav.data.filedirectory

import com.example.comicdav.security.CredentialCipher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

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

    suspend fun updateWebDavDirectory(
        id: Long,
        displayName: String,
        accountId: String,
        path: String,
        baseUrl: String,
        username: String,
        password: String,
    ) = Unit
}

class FileDirectoryRepository(
    private val dao: FileDirectoryDao,
    private val cipher: CredentialCipher? = null,
    private val clock: () -> Long = { System.currentTimeMillis() },
) : FileDirectoryCatalog {
    override fun observeSources(): Flow<List<FileDirectorySourceEntity>> =
        dao.observeSources().map { sources ->
            sources.map { entity -> decryptEntity(entity) }
        }

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
        val encryptedPassword = if (password.isNotEmpty() && cipher != null) cipher.encrypt(password) else password
        return dao.insertSource(
            FileDirectorySourceEntity(
                displayName = displayName,
                sourceType = FileDirectorySourceType.WEBDAV,
                webDavAccountId = accountId,
                webDavPath = path,
                webDavBaseUrl = baseUrl.ifEmpty { null },
                webDavUsername = username.ifEmpty { null },
                webDavPassword = encryptedPassword.ifEmpty { null },
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
        baseUrl: String,
        username: String,
        password: String,
    ) {
        val encryptedPassword = if (password.isNotEmpty() && cipher != null) cipher.encrypt(password) else password
        dao.updateWebDavSource(
            id = id,
            displayName = displayName,
            accountId = accountId,
            path = path,
            baseUrl = baseUrl.ifEmpty { null },
            username = username.ifEmpty { null },
            password = encryptedPassword.ifEmpty { null },
        )
    }

    private suspend fun decryptEntity(entity: FileDirectorySourceEntity): FileDirectorySourceEntity {
        val storedPassword = entity.webDavPassword ?: return entity
        if (cipher == null) return entity
        val decrypted = cipher.decrypt(storedPassword)
        // Lazy migration: if stored value was plaintext, re-encrypt in DB
        if (!storedPassword.startsWith("v1:")) {
            dao.updateWebDavPassword(entity.id, cipher.encrypt(decrypted))
        }
        return entity.copy(webDavPassword = decrypted)
    }
}
