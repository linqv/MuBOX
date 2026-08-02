package org.mubox.reader.feature.reader

import org.mubox.reader.core.io.FileLruPruner
import java.io.File

internal object ReaderPageCache {
    fun pageFile(cacheDir: File, pageCacheKey: String?, pageIndex: Int): File {
        val safeKey = safeCacheKey(pageCacheKey)
        val pageDir = File(cacheDir, "mubox-reader-pages/$safeKey")
        pageDir.mkdirs()
        return File(pageDir, "page-$pageIndex.img")
    }

    fun transientPageFile(cacheDir: File, readerKey: String?, pageIndex: Int): File {
        val safeKey = safeCacheKey(readerKey)
        val pageDir = File(cacheDir, "mubox-reader-pages-transient/$safeKey")
        pageDir.mkdirs()
        return File(pageDir, "page-$pageIndex.img")
    }

    fun clearTransientPages(cacheDir: File, readerKey: String? = null) {
        val root = readerKey
            ?.let { File(cacheDir, "mubox-reader-pages-transient/${safeCacheKey(it)}") }
            ?: File(cacheDir, "mubox-reader-pages-transient")
        root.deleteRecursively()
    }

    fun clearComicPages(cacheDir: File, comicKey: String): Long {
        val pageCacheKeys = setOf(safeCacheKey(comicKey))
        val persistentTargets = pageCacheKeys.map { safeKey ->
            File(cacheDir, "mubox-reader-pages/$safeKey")
        }
        val transientTargets = File(cacheDir, "mubox-reader-pages-transient")
            .listFiles()
            ?.filter { candidate ->
                candidate.isDirectory && candidate.name.isTransientDirectoryFor(pageCacheKeys)
            }
            .orEmpty()
        return deleteTargets(persistentTargets + transientTargets)
    }

    fun prune(cacheDir: File, protectedFile: File? = null): Int {
        return prune(cacheDir = cacheDir, protectedFile = protectedFile, maxBytes = DEFAULT_MAX_BYTES)
    }

    fun prune(cacheDir: File, protectedFile: File? = null, maxBytes: Long): Int {
        val protectedFiles = protectedFile?.let { setOf(it) }.orEmpty()
        return FileLruPruner.prune(
            root = File(cacheDir, "mubox-reader-pages"),
            maxBytes = maxBytes,
            protectedFiles = protectedFiles,
        )
    }

    internal const val DEFAULT_MAX_BYTES = 1L * 1024L * 1024L * 1024L

    private fun safeCacheKey(cacheKey: String?): String =
        (cacheKey ?: "default").replace(Regex("[^A-Za-z0-9._-]"), "_")

    private fun String.isTransientDirectoryFor(pageCacheKeys: Set<String>): Boolean {
        val separatorIndex = lastIndexOf('_')
        if (separatorIndex <= 0 || separatorIndex == lastIndex) return false
        val pageCacheKey = substring(0, separatorIndex)
        val generation = substring(separatorIndex + 1)
        return pageCacheKey in pageCacheKeys && generation.all(Char::isDigit)
    }

    private fun deleteTargets(targets: List<File>): Long =
        targets
            .distinctBy { target -> target.absolutePath }
            .sumOf { target -> target.deleteAndReturnBytes() }

    private fun File.deleteAndReturnBytes(): Long {
        if (!exists()) return 0L
        val bytes = if (isFile) length() else walkTopDown().filter(File::isFile).sumOf(File::length)
        val deleted = if (isDirectory) deleteRecursively() else delete()
        return if (deleted) bytes else 0L
    }
}

/** App-level cache maintenance entry point; page file layout stays feature-internal. */
fun pruneReaderPageCache(cacheDir: File, maxBytes: Long): Int =
    ReaderPageCache.prune(cacheDir = cacheDir, maxBytes = maxBytes)

/** Removes persistent and transient pages for one comic without exposing the cache layout. */
fun clearReaderPageCacheForComic(cacheDir: File, comicKey: String): Long =
    ReaderPageCache.clearComicPages(cacheDir = cacheDir, comicKey = comicKey)
