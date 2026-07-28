package com.example.comicdav.core.ports

import com.example.comicdav.core.model.source.FileDirectorySource
import kotlinx.coroutines.flow.Flow

interface FileDirectoryCatalog {
    fun observeSources(): Flow<List<FileDirectorySource>>

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
