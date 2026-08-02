package org.mubox.reader.core.model.library

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

data class LocalComicSource(
    val libraryItemId: Long,
    val uri: String,
    val fileName: String,
    val size: Long? = null,
    val lastModified: Long? = null,
)

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

data class LibraryItemWithSources(
    val item: LibraryItem,
    val localSource: LocalComicSource?,
    val webDavSource: WebDavComicSource?,
)
