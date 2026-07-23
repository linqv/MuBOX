package com.example.comicdav.data.filedirectory

enum class FileDirectorySourceType {
    LOCAL,
    WEBDAV,
}

/** A saved directory source exposed outside the persistence layer. */
data class FileDirectorySource(
    val id: Long = 0L,
    val displayName: String,
    val sourceType: FileDirectorySourceType,
    val localTreeUri: String? = null,
    val webDavAccountId: String? = null,
    val webDavPath: String? = null,
    val webDavBaseUrl: String? = null,
    val webDavUsername: String? = null,
    val webDavPassword: String? = null,
    val addedAt: Long,
)

internal fun FileDirectorySourceEntity.toDomain(): FileDirectorySource {
    return FileDirectorySource(
        id = id,
        displayName = displayName,
        sourceType = sourceType,
        localTreeUri = localTreeUri,
        webDavAccountId = webDavAccountId,
        webDavPath = webDavPath,
        webDavBaseUrl = webDavBaseUrl,
        webDavUsername = webDavUsername,
        webDavPassword = webDavPassword,
        addedAt = addedAt,
    )
}
