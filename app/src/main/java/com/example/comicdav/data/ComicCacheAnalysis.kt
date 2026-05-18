package com.example.comicdav.data

import java.io.File
import java.util.Locale

data class ComicCacheAnalysis(
    val remoteDownloadsBytes: Long = 0,
    val remoteIndexBytes: Long = 0,
    val readerPagesBytes: Long = 0,
    val libraryCoversBytes: Long = 0,
) {
    val totalBytes: Long
        get() = remoteDownloadsBytes + remoteIndexBytes + readerPagesBytes + libraryCoversBytes
}

data class CacheClearResult(
    val filesDeleted: Int,
    val bytesDeleted: Long,
)

enum class ComicCacheCategory {
    REMOTE_DOWNLOADS,
    REMOTE_INDEX,
    READER_PAGES,
    LIBRARY_COVERS,
}

fun analyzeComicCache(cacheDir: File): ComicCacheAnalysis {
    return ComicCacheAnalysis(
        remoteDownloadsBytes = cacheDir.resolve("remote-comics").directorySize(
            excludedRoots = setOf(cacheDir.resolve("remote-comics/index")),
        ),
        remoteIndexBytes = cacheDir.resolve("remote-comics/index").directorySize(),
        readerPagesBytes = cacheDir.resolve("comicdav-pages").directorySize(),
        libraryCoversBytes = cacheDir.resolve("library-covers").directorySize(),
    )
}

fun clearComicCache(cacheDir: File): CacheClearResult {
    return ComicCacheCategory.entries
        .map { category -> clearComicCacheCategory(cacheDir, category) }
        .fold(CacheClearResult(filesDeleted = 0, bytesDeleted = 0L)) { total, result ->
            CacheClearResult(
                filesDeleted = total.filesDeleted + result.filesDeleted,
                bytesDeleted = total.bytesDeleted + result.bytesDeleted,
            )
        }
}

fun clearComicCacheCategory(cacheDir: File, category: ComicCacheCategory): CacheClearResult {
    val targets = category.targets(cacheDir)
    var filesDeleted = 0
    var bytesDeleted = 0L
    targets.forEach { target ->
        target.root.walkExistingFiles(excludedRoots = target.excludedRoots).forEach { file ->
            val bytes = file.length()
            if (file.delete()) {
                filesDeleted += 1
                bytesDeleted += bytes
            }
        }
        target.root.deleteEmptyDirectories(excludedRoots = target.excludedRoots)
    }
    return CacheClearResult(filesDeleted = filesDeleted, bytesDeleted = bytesDeleted)
}

fun formatCacheSize(bytes: Long): String {
    if (bytes <= 0L) return "0 B"
    val units = listOf("B", "KB", "MB", "GB")
    var unitIndex = 0
    var value = bytes.toDouble()
    while (value >= 1024.0 && unitIndex < units.lastIndex) {
        value /= 1024.0
        unitIndex += 1
    }
    return if (unitIndex == 0) {
        "${bytes} B"
    } else {
        String.format(Locale.US, "%.1f %s", value, units[unitIndex])
    }
}

private data class CacheTarget(
    val root: File,
    val excludedRoots: Set<File> = emptySet(),
)

private fun ComicCacheCategory.targets(cacheDir: File): List<CacheTarget> =
    when (this) {
        ComicCacheCategory.REMOTE_DOWNLOADS -> listOf(
            CacheTarget(
                root = cacheDir.resolve("remote-comics"),
                excludedRoots = setOf(cacheDir.resolve("remote-comics/index")),
            ),
        )
        ComicCacheCategory.REMOTE_INDEX -> listOf(CacheTarget(cacheDir.resolve("remote-comics/index")))
        ComicCacheCategory.READER_PAGES -> listOf(CacheTarget(cacheDir.resolve("comicdav-pages")))
        ComicCacheCategory.LIBRARY_COVERS -> listOf(CacheTarget(cacheDir.resolve("library-covers")))
    }

private fun File.directorySize(excludedRoots: Set<File> = emptySet()): Long =
    walkExistingFiles(excludedRoots).sumOf { it.length() }

private fun File.walkExistingFiles(excludedRoots: Set<File> = emptySet()): Sequence<File> {
    if (!exists()) return emptySequence()
    val excludedCanonicalRoots = excludedRoots.canonicalFiles()
    return walkTopDown()
        .onEnter { directory -> directory.canonicalOrAbsolute() !in excludedCanonicalRoots }
        .filter { it.isFile }
}

private fun File.deleteEmptyDirectories(excludedRoots: Set<File> = emptySet()) {
    if (!exists()) return
    val excludedCanonicalRoots = excludedRoots.canonicalFiles()
    walkBottomUp()
        .filter { it.isDirectory }
        .filter { it.canonicalOrAbsolute() !in excludedCanonicalRoots }
        .forEach { directory ->
            runCatching { directory.delete() }
        }
}

private fun Set<File>.canonicalFiles(): Set<File> =
    map { it.canonicalOrAbsolute() }.toSet()

private fun File.canonicalOrAbsolute(): File =
    runCatching { canonicalFile }.getOrDefault(absoluteFile)
