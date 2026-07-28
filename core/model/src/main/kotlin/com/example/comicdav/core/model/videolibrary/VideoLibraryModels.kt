package com.example.comicdav.core.model.videolibrary

enum class VideoSourceType {
    LOCAL,
    WEBDAV,
}

data class VideoLibraryItem(
    val id: Long = 0L,
    val title: String,
    val displayName: String,
    val sourceType: VideoSourceType,
    val thumbnailPath: String? = null,
    val addedAt: Long,
    val lastOpenedAt: Long? = null,
)

data class LocalVideoSource(
    val videoLibraryItemId: Long,
    val uri: String,
    val fileName: String,
    val size: Long? = null,
    val lastModified: Long? = null,
)

data class WebDavVideoSource(
    val videoLibraryItemId: Long,
    val accountId: String,
    val remotePath: String,
    val fileName: String,
    val size: Long? = null,
    val etag: String? = null,
    val lastModified: Long? = null,
)

data class VideoLibraryItemWithSources(
    val item: VideoLibraryItem,
    val localSource: LocalVideoSource?,
    val webDavSource: WebDavVideoSource?,
)
