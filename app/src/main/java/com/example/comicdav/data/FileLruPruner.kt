package com.example.comicdav.data

import java.io.File

object FileLruPruner {
    fun prune(
        root: File,
        maxBytes: Long,
        protectedFiles: Set<File> = emptySet(),
    ): Int {
        if (maxBytes <= 0L || !root.exists()) return 0
        val protectedCanonical = protectedFiles.mapNotNull { file ->
            runCatching { file.canonicalFile }.getOrNull()
        }.toSet()
        val files = root
            .walkTopDown()
            .filter { it.isFile }
            .map { file ->
                CachedFile(
                    file = file,
                    canonicalFile = runCatching { file.canonicalFile }.getOrDefault(file.absoluteFile),
                    bytes = file.length(),
                    modified = file.lastModified(),
                )
            }
            .toList()
        var totalBytes = files.sumOf { it.bytes }
        var removed = 0
        files
            .filterNot { it.canonicalFile in protectedCanonical }
            .sortedWith(compareBy<CachedFile> { it.modified }.thenBy { it.file.absolutePath })
            .forEach { cached ->
                if (totalBytes <= maxBytes) return@forEach
                if (cached.file.delete()) {
                    totalBytes = (totalBytes - cached.bytes).coerceAtLeast(0L)
                    removed += 1
                }
            }
        return removed
    }

    private data class CachedFile(
        val file: File,
        val canonicalFile: File,
        val bytes: Long,
        val modified: Long,
    )
}
