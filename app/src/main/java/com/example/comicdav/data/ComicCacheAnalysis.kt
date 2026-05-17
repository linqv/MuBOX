package com.example.comicdav.data

import java.io.File
import java.util.Locale

data class ComicCacheAnalysis(
    val remoteDownloadsBytes: Long = 0,
    val readerPagesBytes: Long = 0,
) {
    val totalBytes: Long
        get() = remoteDownloadsBytes + readerPagesBytes
}

data class CacheClearResult(
    val filesDeleted: Int,
    val bytesDeleted: Long,
)

fun analyzeComicCache(cacheDir: File): ComicCacheAnalysis {
    return ComicCacheAnalysis(
        remoteDownloadsBytes = cacheDir.resolve("remote-comics").directorySize(),
        readerPagesBytes = cacheDir.resolve("comicdav-pages").directorySize(),
    )
}

fun clearComicCache(cacheDir: File): CacheClearResult {
    val targets = listOf(
        cacheDir.resolve("remote-comics"),
        cacheDir.resolve("comicdav-pages"),
    )
    var filesDeleted = 0
    var bytesDeleted = 0L
    targets.forEach { root ->
        root.walkExistingFiles().forEach { file ->
            val bytes = file.length()
            if (file.delete()) {
                filesDeleted += 1
                bytesDeleted += bytes
            }
        }
        root.deleteEmptyDirectories()
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

private fun File.directorySize(): Long =
    walkExistingFiles().sumOf { it.length() }

private fun File.walkExistingFiles(): Sequence<File> {
    if (!exists()) return emptySequence()
    return walkTopDown().filter { it.isFile }
}

private fun File.deleteEmptyDirectories() {
    if (!exists()) return
    walkBottomUp()
        .filter { it.isDirectory }
        .forEach { directory ->
            runCatching { directory.delete() }
        }
}
