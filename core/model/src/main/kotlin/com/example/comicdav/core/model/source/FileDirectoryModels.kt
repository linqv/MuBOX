package com.example.comicdav.core.model.source

enum class FileDirectorySourceType {
    LOCAL,
    WEBDAV,
}

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
