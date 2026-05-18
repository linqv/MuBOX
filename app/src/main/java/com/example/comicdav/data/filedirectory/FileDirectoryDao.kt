package com.example.comicdav.data.filedirectory

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface FileDirectoryDao {
    @Insert
    suspend fun insertSource(source: FileDirectorySourceEntity): Long

    @Query("DELETE FROM file_directory_sources WHERE id = :id")
    suspend fun deleteSource(id: Long)

    @Query(
        """
        UPDATE file_directory_sources
        SET displayName = :displayName,
            webDavAccountId = :accountId,
            webDavPath = :path,
            webDavBaseUrl = :baseUrl,
            webDavUsername = :username,
            webDavPassword = :password
        WHERE id = :id AND sourceType = 'WEBDAV'
        """,
    )
    suspend fun updateWebDavSource(
        id: Long,
        displayName: String,
        accountId: String,
        path: String,
        baseUrl: String?,
        username: String?,
        password: String?,
    )

    @Query("SELECT * FROM file_directory_sources ORDER BY addedAt DESC, id DESC")
    fun observeSources(): Flow<List<FileDirectorySourceEntity>>
}
