package org.mubox.reader.core.model.media

import org.mubox.reader.core.crypto.sha256Hex
import org.mubox.reader.core.model.videolibrary.VideoLibraryItemWithSources
import org.mubox.reader.core.model.videolibrary.VideoSourceType
import org.mubox.reader.core.remote.WebDavItem
import java.io.File

const val VIDEO_THUMBNAIL_CACHE_SUBDIRECTORY = "video-library-thumbnails"

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

fun hasReliableFileDirectoryVideoThumbnailVersion(lastModified: Long?): Boolean =
    (lastModified ?: 0L) > 0L

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
    return if (hasReliableFileDirectoryVideoThumbnailVersion(lastModified)) {
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

fun webDavVideoThumbnailStableKey(
    accountId: String,
    remotePath: String,
    size: Long?,
    etag: String?,
    lastModified: Long?,
): String = "webdav:$accountId:$remotePath:${size ?: -1}:${etag.orEmpty()}:${lastModified ?: -1}"

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

fun videoThumbnailStableKey(item: VideoLibraryItemWithSources): String? =
    when (item.item.sourceType) {
        VideoSourceType.LOCAL -> item.localSource?.let { source ->
            fileDirectoryVideoThumbnailVersion(
                uri = source.uri,
                size = source.size,
                lastModified = source.lastModified,
            )
        }
        VideoSourceType.WEBDAV -> item.webDavSource?.let { source ->
            webDavVideoThumbnailStableKey(
                accountId = source.accountId,
                remotePath = source.remotePath,
                size = source.size,
                etag = source.etag,
                lastModified = source.lastModified,
            )
        }
    }

fun videoThumbnailFileNameForStableKey(stableKey: String): String {
    val readablePrefix = stableKey
        .replace(Regex("[^A-Za-z0-9._-]"), "_")
        .trim('_', '.', '-')
        .take(48)
    val hash = stableKey.sha256Hex()
    return if (readablePrefix.isBlank()) {
        "$hash.jpg"
    } else {
        "$readablePrefix-$hash.jpg"
    }
}

fun videoThumbnailFile(cacheDir: File, stableKey: String): File =
    cacheDir
        .resolve(VIDEO_THUMBNAIL_CACHE_SUBDIRECTORY)
        .resolve(videoThumbnailFileNameForStableKey(stableKey))

fun resolvedVideoThumbnailPath(
    item: VideoLibraryItemWithSources,
    cacheDir: File,
): String? =
    item.item.thumbnailPath
        ?.let(::File)
        ?.takeIf { it.isFile && it.length() > 0L }
        ?.absolutePath
        ?: videoThumbnailStableKey(item)
            ?.let { stableKey -> videoThumbnailFile(cacheDir, stableKey) }
            ?.takeIf { it.isFile && it.length() > 0L }
            ?.absolutePath
