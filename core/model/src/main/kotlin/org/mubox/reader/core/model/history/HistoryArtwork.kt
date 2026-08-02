package org.mubox.reader.core.model.history

import org.mubox.reader.core.model.library.LibraryItemWithSources
import org.mubox.reader.core.model.media.fileDirectoryVideoThumbnailVersion
import org.mubox.reader.core.model.media.videoThumbnailFile
import org.mubox.reader.core.model.media.webDavVideoThumbnailStableKey
import org.mubox.reader.core.model.videolibrary.VideoLibraryItemWithSources
import java.io.File
import java.security.MessageDigest

fun historyThumbnailStableKey(entry: WatchHistoryEntry): String =
    when (entry.mediaType) {
        WatchMediaType.VIDEO -> when (entry.sourceType) {
            WatchSourceType.LOCAL -> fileDirectoryVideoThumbnailVersion(
                uri = entry.sourceLocator,
                size = entry.size,
                lastModified = entry.lastModified,
            )
            WatchSourceType.WEB_DAV -> webDavVideoThumbnailStableKey(
                accountId = entry.accountId.orEmpty(),
                remotePath = entry.sourceLocator,
                size = entry.size,
                etag = entry.etag,
                lastModified = entry.lastModified,
            )
        }
        WatchMediaType.COMIC -> listOf(
            "history",
            entry.mediaType.name,
            entry.mediaKey,
            entry.sourceLocator,
            entry.accountId.orEmpty(),
            entry.size?.toString().orEmpty(),
            entry.etag.orEmpty(),
            entry.lastModified?.toString().orEmpty(),
        ).joinToString(separator = "\u001F")
    }

fun historyThumbnailFile(
    cacheDir: File,
    entry: WatchHistoryEntry,
): File {
    val stableKey = historyThumbnailStableKey(entry)
    if (entry.mediaType == WatchMediaType.VIDEO) {
        return videoThumbnailFile(cacheDir, stableKey)
    }
    val readablePrefix = stableKey
        .replace(Regex("[^A-Za-z0-9._-]"), "_")
        .trim('_', '.', '-')
        .take(48)
    val hash = stableKey.sha256Hex()
    val fileName = if (readablePrefix.isBlank()) {
        "$hash.img"
    } else {
        "$readablePrefix-$hash.img"
    }
    return cacheDir.resolve("history-thumbnails").resolve(fileName)
}

fun libraryArtworkPathForHistory(
    entry: WatchHistoryEntry,
    comics: List<LibraryItemWithSources>,
    videos: List<VideoLibraryItemWithSources>,
): String? =
    when (entry.mediaType) {
        WatchMediaType.COMIC -> comics.firstOrNull { item ->
            when (entry.sourceType) {
                WatchSourceType.LOCAL -> item.localSource?.uri == entry.sourceLocator
                WatchSourceType.WEB_DAV -> item.webDavSource?.let { source ->
                    source.accountId == entry.accountId && source.remotePath == entry.sourceLocator
                } == true
            }
        }?.item?.coverPath
        WatchMediaType.VIDEO -> videos.firstOrNull { item ->
            when (entry.sourceType) {
                WatchSourceType.LOCAL -> item.localSource?.uri == entry.sourceLocator
                WatchSourceType.WEB_DAV -> item.webDavSource?.let { source ->
                    source.accountId == entry.accountId && source.remotePath == entry.sourceLocator
                } == true
            }
        }?.item?.thumbnailPath
    }

fun resolvedHistoryArtworkPath(
    entry: WatchHistoryEntry,
    comics: List<LibraryItemWithSources>,
    videos: List<VideoLibraryItemWithSources>,
    cacheDir: File? = null,
): String? {
    val libraryPath = libraryArtworkPathForHistory(entry, comics, videos)
    if (cacheDir == null) return libraryPath
    return libraryPath
        ?.let(::File)
        ?.takeIf(File::isFile)
        ?.absolutePath
        ?: historyThumbnailFile(cacheDir, entry)
            .takeIf(File::isFile)
            ?.absolutePath
}

fun historyEntriesNeedingThumbnails(
    history: List<WatchHistoryEntry>,
    comics: List<LibraryItemWithSources>,
    videos: List<VideoLibraryItemWithSources>,
    cacheDir: File,
): List<WatchHistoryEntry> =
    history.filter { entry ->
        resolvedHistoryArtworkPath(
            entry = entry,
            comics = comics,
            videos = videos,
            cacheDir = cacheDir,
        ) == null
    }

private fun String.sha256Hex(): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(toByteArray(Charsets.UTF_8))
    return digest.joinToString(separator = "") { byte -> "%02x".format(byte) }
}
