package com.example.comicdav.data.filedirectory

import com.example.comicdav.core.model.source.FileDirectorySource

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
