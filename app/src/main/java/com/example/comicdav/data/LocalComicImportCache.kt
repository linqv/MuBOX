package com.example.comicdav.data

import java.io.File

object LocalComicImportCache {
    fun targetFile(cacheDir: File, nowMs: Long = System.currentTimeMillis()): File {
        return File(localImportDir(cacheDir), "local-comic-$nowMs.cbz")
    }

    fun prune(
        cacheDir: File,
        maxBytes: Long = DEFAULT_MAX_BYTES,
        protectedFile: File? = null,
    ): Int {
        val protectedFiles = protectedFile?.let { setOf(it) }.orEmpty()
        return FileLruPruner.prune(localImportDir(cacheDir), maxBytes, protectedFiles)
    }

    private fun localImportDir(cacheDir: File): File =
        File(cacheDir, "local-comics")

    private const val DEFAULT_MAX_BYTES = 512L * 1024L * 1024L
}
