package org.mubox.reader.data.videolibrary

import org.mubox.reader.core.model.videolibrary.LocalVideoSource
import org.mubox.reader.core.model.videolibrary.VideoLibraryItem
import org.mubox.reader.core.model.videolibrary.VideoLibraryItemWithSources
import org.mubox.reader.core.model.videolibrary.WebDavVideoSource

internal fun VideoLibraryItemRelation.toDomain(): VideoLibraryItemWithSources {
    return VideoLibraryItemWithSources(
        item = item.toDomain(),
        localSource = localSource?.toDomain(),
        webDavSource = webDavSource?.toDomain(),
    )
}

private fun VideoLibraryItemEntity.toDomain(): VideoLibraryItem {
    return VideoLibraryItem(
        id = id,
        title = title,
        displayName = displayName,
        sourceType = sourceType,
        thumbnailPath = thumbnailPath,
        addedAt = addedAt,
        lastOpenedAt = lastOpenedAt,
    )
}

private fun LocalVideoSourceEntity.toDomain(): LocalVideoSource {
    return LocalVideoSource(
        videoLibraryItemId = videoLibraryItemId,
        uri = uri,
        fileName = fileName,
        size = size,
        lastModified = lastModified,
    )
}

private fun WebDavVideoSourceEntity.toDomain(): WebDavVideoSource {
    return WebDavVideoSource(
        videoLibraryItemId = videoLibraryItemId,
        accountId = accountId,
        remotePath = remotePath,
        fileName = fileName,
        size = size,
        etag = etag,
        lastModified = lastModified,
    )
}
