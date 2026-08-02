package org.mubox.reader.data.library

import org.mubox.reader.core.model.library.LibraryItem
import org.mubox.reader.core.model.library.LibraryItemWithSources
import org.mubox.reader.core.model.library.LocalComicSource
import org.mubox.reader.core.model.library.WebDavComicSource

internal fun LibraryItemRelation.toDomain(): LibraryItemWithSources {
    return LibraryItemWithSources(
        item = item.toDomain(),
        localSource = localSource?.toDomain(),
        webDavSource = webDavSource?.toDomain(),
    )
}

private fun LibraryItemEntity.toDomain(): LibraryItem {
    return LibraryItem(
        id = id,
        title = title,
        displayName = displayName,
        seriesTitle = seriesTitle,
        volumeTitle = volumeTitle,
        sourceType = sourceType,
        coverPath = coverPath,
        pageCount = pageCount,
        lastPageIndex = lastPageIndex,
        addedAt = addedAt,
        lastOpenedAt = lastOpenedAt,
        offlineState = offlineState,
    )
}

private fun LocalComicSourceEntity.toDomain(): LocalComicSource {
    return LocalComicSource(
        libraryItemId = libraryItemId,
        uri = uri,
        fileName = fileName,
        size = size,
        lastModified = lastModified,
    )
}

private fun WebDavComicSourceEntity.toDomain(): WebDavComicSource {
    return WebDavComicSource(
        libraryItemId = libraryItemId,
        accountId = accountId,
        remotePath = remotePath,
        fileName = fileName,
        size = size,
        etag = etag,
        lastModified = lastModified,
        cacheKey = cacheKey,
    )
}
