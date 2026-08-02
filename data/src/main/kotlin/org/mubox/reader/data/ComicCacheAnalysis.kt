package org.mubox.reader.data

import org.mubox.reader.core.model.cache.CacheClearResult
import org.mubox.reader.core.model.cache.ComicCacheAnalysis
import org.mubox.reader.core.model.cache.ComicCacheCategory
import java.io.File

fun analyzeComicCache(
    cacheDir: File,
    codeCacheDir: File? = null,
    externalCacheDirs: List<File> = emptyList(),
): ComicCacheAnalysis {
    val distinctExternalCacheDirs = distinctCacheRoots(externalCacheDirs)
    return ComicCacheAnalysis(
        remoteDownloadsBytes = cacheDir.resolve("remote-comics").directorySize(
            excludedRoots = setOf(cacheDir.resolve("remote-comics/index")),
        ),
        remoteIndexBytes = cacheDir.resolve("remote-comics/index").directorySize(),
        readerPagesBytes = cacheDir.resolve("mubox-reader-pages").directorySize(),
        transientReaderPagesBytes = cacheDir.resolve("mubox-reader-pages-transient").directorySize(),
        libraryCoversBytes = cacheDir.resolve("library-covers").directorySize(),
        videoThumbnailsBytes = cacheDir.resolve("video-library-thumbnails").directorySize(),
        historyThumbnailsBytes = cacheDir.resolve("history-thumbnails").directorySize(),
        videoSubtitlesBytes = cacheDir.resolve("video-subtitles").directorySize(),
        codeCacheBytes = codeCacheDir?.directorySize() ?: 0L,
        externalCacheBytes = distinctExternalCacheDirs.sumOf { it.directorySize() },
        otherBytes = cacheDir.directorySize(excludedRoots = knownTopLevelCacheRoots(cacheDir)),
    )
}

fun clearComicCache(
    cacheDir: File,
    codeCacheDir: File? = null,
    externalCacheDirs: List<File> = emptyList(),
): CacheClearResult {
    val roots = distinctCacheRoots(listOf(cacheDir, codeCacheDir) + externalCacheDirs)
    return clearCacheTargets(roots.map { CacheTarget(root = it, preserveRoot = true) })
}

fun clearComicCacheCategory(
    cacheDir: File,
    category: ComicCacheCategory,
    codeCacheDir: File? = null,
    externalCacheDirs: List<File> = emptyList(),
): CacheClearResult {
    return clearCacheTargets(category.targets(cacheDir, codeCacheDir, externalCacheDirs))
}

private fun clearCacheTargets(targets: List<CacheTarget>): CacheClearResult {
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
        target.root.deleteEmptyDirectories(
            excludedRoots = target.excludedRoots,
            preserveRoot = target.preserveRoot,
        )
    }
    return CacheClearResult(filesDeleted = filesDeleted, bytesDeleted = bytesDeleted)
}

private data class CacheTarget(
    val root: File,
    val excludedRoots: Set<File> = emptySet(),
    val preserveRoot: Boolean = false,
)

private fun ComicCacheCategory.targets(
    cacheDir: File,
    codeCacheDir: File?,
    externalCacheDirs: List<File>,
): List<CacheTarget> =
    when (this) {
        ComicCacheCategory.REMOTE_DOWNLOADS -> listOf(
            CacheTarget(
                root = cacheDir.resolve("remote-comics"),
                excludedRoots = setOf(cacheDir.resolve("remote-comics/index")),
            ),
        )
        ComicCacheCategory.REMOTE_INDEX -> listOf(CacheTarget(cacheDir.resolve("remote-comics/index")))
        ComicCacheCategory.READER_PAGES -> listOf(CacheTarget(cacheDir.resolve("mubox-reader-pages")))
        ComicCacheCategory.TRANSIENT_READER_PAGES ->
            listOf(CacheTarget(cacheDir.resolve("mubox-reader-pages-transient")))
        ComicCacheCategory.LIBRARY_COVERS -> listOf(CacheTarget(cacheDir.resolve("library-covers")))
        ComicCacheCategory.VIDEO_THUMBNAILS -> listOf(CacheTarget(cacheDir.resolve("video-library-thumbnails")))
        ComicCacheCategory.HISTORY_THUMBNAILS -> listOf(CacheTarget(cacheDir.resolve("history-thumbnails")))
        ComicCacheCategory.VIDEO_SUBTITLES -> listOf(CacheTarget(cacheDir.resolve("video-subtitles")))
        ComicCacheCategory.CODE_CACHE -> codeCacheDir
            ?.let { listOf(CacheTarget(root = it, preserveRoot = true)) }
            .orEmpty()
        ComicCacheCategory.EXTERNAL_CACHE -> distinctCacheRoots(externalCacheDirs)
            .map { CacheTarget(root = it, preserveRoot = true) }
        ComicCacheCategory.OTHER -> listOf(
            CacheTarget(
                root = cacheDir,
                excludedRoots = knownTopLevelCacheRoots(cacheDir),
                preserveRoot = true,
            ),
        )
    }

private fun knownTopLevelCacheRoots(cacheDir: File): Set<File> =
    setOf(
        cacheDir.resolve("remote-comics"),
        cacheDir.resolve("mubox-reader-pages"),
        cacheDir.resolve("mubox-reader-pages-transient"),
        cacheDir.resolve("library-covers"),
        cacheDir.resolve("video-library-thumbnails"),
        cacheDir.resolve("history-thumbnails"),
        cacheDir.resolve("video-subtitles"),
    )

private fun File.directorySize(excludedRoots: Set<File> = emptySet()): Long =
    walkExistingFiles(excludedRoots).sumOf { it.length() }

private fun File.walkExistingFiles(excludedRoots: Set<File> = emptySet()): Sequence<File> {
    if (!exists()) return emptySequence()
    val excludedCanonicalRoots = excludedRoots.canonicalFiles()
    return walkTopDown()
        .onEnter { directory -> directory.canonicalOrAbsolute() !in excludedCanonicalRoots }
        .filter { it.isFile }
}

private fun File.deleteEmptyDirectories(
    excludedRoots: Set<File> = emptySet(),
    preserveRoot: Boolean = false,
) {
    if (!exists()) return
    val root = canonicalOrAbsolute()
    val excludedCanonicalRoots = excludedRoots.canonicalFiles()
    walkBottomUp()
        .filter { it.isDirectory }
        .map { it to it.canonicalOrAbsolute() }
        .filter { (_, canonicalDirectory) -> canonicalDirectory !in excludedCanonicalRoots }
        .filter { (_, canonicalDirectory) -> !preserveRoot || canonicalDirectory != root }
        .forEach { (directory, _) ->
            runCatching { directory.delete() }
        }
}

private fun distinctCacheRoots(roots: List<File?>): List<File> =
    roots.filterNotNull().distinctBy { it.canonicalOrAbsolute().absolutePath }

private fun Set<File>.canonicalFiles(): Set<File> =
    map { it.canonicalOrAbsolute() }.toSet()

private fun File.canonicalOrAbsolute(): File =
    runCatching { canonicalFile }.getOrDefault(absoluteFile)
