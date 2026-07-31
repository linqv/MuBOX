package com.example.comicdav

import com.example.comicdav.core.model.videolibrary.VideoLibraryItemWithSources
import com.example.comicdav.core.ports.VideoLibraryCatalog
import com.example.comicdav.core.remote.WebDavItem
import com.example.comicdav.feature.filedirectory.FileDirectoryBrowserItem

/** Maps app video commands onto the domain-facing video library port. */
internal class AppVideoLibraryCoordinator(
    private val catalog: VideoLibraryCatalog,
) {
    suspend fun addLocal(
        item: FileDirectoryBrowserItem,
        thumbnailPath: String?,
    ) {
        catalog.addLocalVideo(
            uri = item.uri,
            fileName = item.name,
            size = item.size,
            lastModified = item.lastModified,
            thumbnailPath = thumbnailPath,
        )
    }

    suspend fun addWebDav(
        accountId: String,
        item: WebDavItem,
        thumbnailPath: String?,
    ) {
        catalog.addWebDavVideo(
            accountId = accountId,
            remotePath = item.path,
            fileName = item.name,
            size = item.size,
            etag = item.etag,
            lastModified = item.lastModified,
            thumbnailPath = thumbnailPath,
        )
    }

    suspend fun remove(item: VideoLibraryItemWithSources) {
        catalog.removeVideo(item.item.id)
    }

    suspend fun updateThumbnail(
        item: VideoLibraryItemWithSources,
        thumbnailPath: String?,
    ) {
        catalog.updateThumbnailPath(item.item.id, thumbnailPath)
    }
}
