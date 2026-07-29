package com.example.comicdav.core.model.media

import com.example.comicdav.core.remote.WebDavItem

/**
 * Stable thumbnail identity shared by browsers, library actions, and cache writers.
 *
 * Keeping this rule in the core contract prevents individual UI features from
 * becoming the source of truth for cache compatibility.
 */
fun fileDirectoryVideoThumbnailVersion(
    uri: String,
    size: Long?,
    lastModified: Long?,
): String = "local:$uri:${size ?: -1}:${lastModified ?: -1}"

fun fileDirectoryVideoThumbnailVersion(item: MediaEntry): String =
    fileDirectoryVideoThumbnailVersion(
        uri = item.uri,
        size = item.size,
        lastModified = item.lastModified,
    )

fun fileDirectoryBrowserVideoThumbnailVersion(
    uri: String,
    size: Long?,
    lastModified: Long?,
    requestRevision: Long,
): String {
    val version = fileDirectoryVideoThumbnailVersion(
        uri = uri,
        size = size,
        lastModified = lastModified,
    )
    return if (lastModified != null && lastModified > 0L) {
        version
    } else {
        "$version:directory-revision:$requestRevision"
    }
}

fun fileDirectoryBrowserVideoThumbnailVersion(
    item: MediaEntry,
    requestRevision: Long,
): String =
    fileDirectoryBrowserVideoThumbnailVersion(
        uri = item.uri,
        size = item.size,
        lastModified = item.lastModified,
        requestRevision = requestRevision,
    )

fun webDavVideoThumbnailVersion(
    path: String,
    size: Long?,
    etag: String?,
    lastModified: Long?,
): String = "webdav:$path:${size ?: -1}:${etag.orEmpty()}:${lastModified ?: -1}"

fun webDavVideoThumbnailVersion(item: WebDavItem): String =
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
