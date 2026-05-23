package com.example.comicdav.feature.reader

import com.example.comicdav.data.FileLruPruner
import java.io.File

internal object ReaderPageCache {
    fun pageFile(cacheDir: File, pageCacheKey: String?, pageIndex: Int): File {
        val safeKey = (pageCacheKey ?: "default").replace(Regex("[^A-Za-z0-9._-]"), "_")
        val pageDir = File(cacheDir, "comicdav-pages/$safeKey")
        pageDir.mkdirs()
        return File(pageDir, "page-$pageIndex.img")
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
}

internal fun readerImageFormatCacheKey(comicKey: String, avifImagesEnabled: Boolean): String =
    if (avifImagesEnabled) "$comicKey-avif" else comicKey
