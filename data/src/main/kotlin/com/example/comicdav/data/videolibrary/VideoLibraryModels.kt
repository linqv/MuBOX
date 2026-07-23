package com.example.comicdav.data.videolibrary

enum class VideoSourceType {
    LOCAL,
    WEBDAV,
}

/** A video-library entry exposed outside the persistence layer. */
data class VideoLibraryItem(
    val id: Long = 0L,
    val title: String,
    val displayName: String,
    val sourceType: VideoSourceType,
    val thumbnailPath: String? = null,
    val addedAt: Long,
    val lastOpenedAt: Long? = null,
)

/** Local video source metadata exposed by the video catalog. */
data class LocalVideoSource(
    val videoLibraryItemId: Long,
    val uri: String,
    val fileName: String,
    val size: Long? = null,
    val lastModified: Long? = null,
)

/** WebDAV video source metadata exposed by the video catalog. */
data class WebDavVideoSource(
    val videoLibraryItemId: Long,
    val accountId: String,
    val remotePath: String,
    val fileName: String,
    val size: Long? = null,
    val etag: String? = null,
    val lastModified: Long? = null,
)

/** Aggregate returned by [VideoLibraryCatalog], intentionally free of Room annotations. */
data class VideoLibraryItemWithSources(
    val item: VideoLibraryItem,
    val localSource: LocalVideoSource?,
    val webDavSource: WebDavVideoSource?,
)

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
