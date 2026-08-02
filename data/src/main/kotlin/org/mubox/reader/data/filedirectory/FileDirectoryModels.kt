package org.mubox.reader.data.filedirectory

import org.mubox.reader.core.model.source.FileDirectorySource

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
