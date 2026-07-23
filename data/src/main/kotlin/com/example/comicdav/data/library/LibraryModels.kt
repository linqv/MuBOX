package com.example.comicdav.data.library

enum class SourceType {
    LOCAL,
    WEBDAV,
}

enum class OfflineState {
    NOT_DOWNLOADED,
    DOWNLOADING,
    DOWNLOADED,
    FAILED,
}

/** A library entry exposed outside the persistence layer. */
data class LibraryItem(
    val id: Long = 0L,
    val title: String,
    val displayName: String,
    val seriesTitle: String? = null,
    val volumeTitle: String? = null,
    val sourceType: SourceType,
    val coverPath: String? = null,
    val pageCount: Int? = null,
    val lastPageIndex: Int = 0,
    val addedAt: Long,
    val lastOpenedAt: Long? = null,
    val offlineState: OfflineState = OfflineState.NOT_DOWNLOADED,
)

/** Local source metadata exposed by the library catalog. */
data class LocalComicSource(
    val libraryItemId: Long,
    val uri: String,
    val fileName: String,
    val size: Long? = null,
    val lastModified: Long? = null,
)

/** WebDAV source metadata exposed by the library catalog. */
data class WebDavComicSource(
    val libraryItemId: Long,
    val accountId: String,
    val remotePath: String,
    val fileName: String,
    val size: Long? = null,
    val etag: String? = null,
    val lastModified: Long? = null,
    val cacheKey: String? = null,
)

/** Aggregate returned by [LibraryCatalog], intentionally free of Room annotations. */
data class LibraryItemWithSources(
    val item: LibraryItem,
    val localSource: LocalComicSource?,
    val webDavSource: WebDavComicSource?,
)

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
