package com.example.comicdav.data.filedirectory

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
internal interface FileDirectoryDao {
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
            webDavBaseUrl = NULL,
            webDavUsername = NULL,
            webDavPassword = NULL
        WHERE id = :id AND sourceType = 'WEBDAV'
        """,
    )
    suspend fun updateWebDavSource(
        id: Long,
        displayName: String,
        accountId: String,
        path: String,
    )

    @Query("SELECT * FROM file_directory_sources ORDER BY addedAt DESC, id DESC")
    fun observeSources(): Flow<List<FileDirectorySourceEntity>>

    @Query(
        """
        SELECT * FROM file_directory_sources
        WHERE sourceType = 'WEBDAV'
          AND (
              webDavBaseUrl IS NOT NULL
              OR webDavUsername IS NOT NULL
              OR webDavPassword IS NOT NULL
          )
        ORDER BY id ASC
        """,
    )
    suspend fun getSourcesWithLegacyCredentials(): List<FileDirectorySourceEntity>

    @Query(
        """
        UPDATE file_directory_sources
        SET webDavAccountId = CASE
                WHEN webDavAccountId IS NULL OR TRIM(webDavAccountId) = '' THEN :accountId
                ELSE webDavAccountId
            END,
            webDavBaseUrl = NULL,
            webDavUsername = NULL,
            webDavPassword = NULL
        WHERE id = :id
          AND sourceType = 'WEBDAV'
          AND webDavBaseUrl = :expectedBaseUrl
        """,
    )
    suspend fun clearLegacyCredentialsAfterMigration(
        id: Long,
        expectedBaseUrl: String,
        accountId: String,
    ): Int
}
