package com.example.comicdav.feature.reader

import com.example.comicdav.core.io.FileLruPruner
import java.io.File

internal object ReaderPageCache {
    fun pageFile(cacheDir: File, pageCacheKey: String?, pageIndex: Int): File {
        val safeKey = safeCacheKey(pageCacheKey)
        val pageDir = File(cacheDir, "comicdav-pages/$safeKey")
        pageDir.mkdirs()
        return File(pageDir, "page-$pageIndex.img")
    }

    fun transientPageFile(cacheDir: File, readerKey: String?, pageIndex: Int): File {
        val safeKey = safeCacheKey(readerKey)
        val pageDir = File(cacheDir, "comicdav-pages-transient/$safeKey")
        pageDir.mkdirs()
        return File(pageDir, "page-$pageIndex.img")
    }

    fun clearTransientPages(cacheDir: File, readerKey: String? = null) {
        val root = readerKey
            ?.let { File(cacheDir, "comicdav-pages-transient/${safeCacheKey(it)}") }
            ?: File(cacheDir, "comicdav-pages-transient")
        root.deleteRecursively()
    }

    fun prune(cacheDir: File, protectedFile: File? = null): Int {
        return prune(cacheDir = cacheDir, protectedFile = protectedFile, maxBytes = DEFAULT_MAX_BYTES)
    }

    fun prune(cacheDir: File, protectedFile: File? = null, maxBytes: Long): Int {
        val protectedFiles = protectedFile?.let { setOf(it) }.orEmpty()
        return FileLruPruner.prune(
            root = File(cacheDir, "comicdav-pages"),
            maxBytes = maxBytes,
            protectedFiles = protectedFiles,
        )
    }

    internal const val DEFAULT_MAX_BYTES = 1L * 1024L * 1024L * 1024L

    private fun safeCacheKey(cacheKey: String?): String =
        (cacheKey ?: "default").replace(Regex("[^A-Za-z0-9._-]"), "_")
}

/** App-level cache maintenance entry point; page file layout stays feature-internal. */
fun pruneReaderPageCache(cacheDir: File, maxBytes: Long): Int =
    ReaderPageCache.prune(cacheDir = cacheDir, maxBytes = maxBytes)
