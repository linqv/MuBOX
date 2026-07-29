package com.example.comicdav.core.model.history

import com.example.comicdav.core.model.library.LibraryItemWithSources
import com.example.comicdav.core.model.videolibrary.VideoLibraryItemWithSources
import java.io.File
import java.security.MessageDigest

fun historyThumbnailStableKey(entry: WatchHistoryEntry): String =
    listOf(
        "history",
        entry.mediaType.name,
        entry.mediaKey,
        entry.sourceLocator,
        entry.accountId.orEmpty(),
        entry.size?.toString().orEmpty(),
        entry.etag.orEmpty(),
        entry.lastModified?.toString().orEmpty(),
    ).joinToString(separator = "\u001F")

fun historyThumbnailFile(
    cacheDir: File,
    entry: WatchHistoryEntry,
): File {
    val stableKey = historyThumbnailStableKey(entry)
    val readablePrefix = stableKey
        .replace(Regex("[^A-Za-z0-9._-]"), "_")
        .trim('_', '.', '-')
        .take(48)
    val hash = stableKey.sha256Hex()
    val extension = if (entry.mediaType == WatchMediaType.VIDEO) "jpg" else "img"
    val fileName = if (readablePrefix.isBlank()) {
        "$hash.$extension"
    } else {
        "$readablePrefix-$hash.$extension"
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
            item.localSource?.uri == entry.sourceLocator ||
                item.webDavSource?.remotePath == entry.sourceLocator
        }?.item?.coverPath
        WatchMediaType.VIDEO -> videos.firstOrNull { item ->
            item.localSource?.uri == entry.sourceLocator ||
                item.webDavSource?.remotePath == entry.sourceLocator
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
