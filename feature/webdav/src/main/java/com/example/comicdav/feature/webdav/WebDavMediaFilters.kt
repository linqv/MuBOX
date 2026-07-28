package com.example.comicdav.feature.webdav

import com.example.comicdav.core.remote.WebDavItem
import com.example.comicdav.core.model.media.MediaKind
import com.example.comicdav.core.model.media.isBrowsableInSources
import com.example.comicdav.core.model.media.mediaKindFor

val WebDavItem.mediaKind: MediaKind
    get() = mediaKindFor(name = name, isDirectory = isDirectory)

internal fun webDavVideoThumbnailVersion(
    path: String,
    size: Long?,
    etag: String?,
    lastModified: Long?,
): String = "webdav:$path:${size ?: -1}:${etag.orEmpty()}:${lastModified ?: -1}"

internal fun webDavVideoThumbnailVersion(item: WebDavItem): String =
    webDavVideoThumbnailVersion(
        path = item.path,
        size = item.size,
        etag = item.etag,
        lastModified = item.lastModified,
    )

fun hasReliableWebDavVideoThumbnailVersion(
    etag: String?,
    lastModified: Long?,
): Boolean = !etag.isNullOrBlank() || (lastModified ?: 0L) > 0L

fun webDavBrowserVideoThumbnailVersion(
    path: String,
    size: Long?,
    etag: String?,
    lastModified: Long?,
    requestRevision: Long,
): String {
    val version = webDavVideoThumbnailVersion(
        path = path,
        size = size,
        etag = etag,
        lastModified = lastModified,
    )
    return if (hasReliableWebDavVideoThumbnailVersion(etag, lastModified)) {
        version
    } else {
        "$version:directory-revision:$requestRevision"
    }
}

fun webDavBrowserVideoThumbnailVersion(
    item: WebDavItem,
    requestRevision: Long,
): String =
    webDavBrowserVideoThumbnailVersion(
        path = item.path,
        size = item.size,
        etag = item.etag,
        lastModified = item.lastModified,
        requestRevision = requestRevision,
    )

internal fun filterBrowsableWebDavItems(items: List<WebDavItem>): List<WebDavItem> =
    items.filter { it.mediaKind.isBrowsableInSources }

fun shouldShowWebDavAccountForm(
    isAddingWebDavPath: Boolean,
    editingWebDavSourceId: Long?,
    webDavStatus: String,
): Boolean =
    webDavStatus != WEB_DAV_STATUS_CONNECTED && (isAddingWebDavPath || editingWebDavSourceId != null)
