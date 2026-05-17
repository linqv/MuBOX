package com.example.comicdav.data

import java.util.Locale

enum class LocalArchiveFormat(
    val nativeName: String,
    private val suffixes: Set<String>,
) {
    Zip("zip", setOf("cbz", "zip")),
    SevenZ("7z", setOf("cb7", "7z")),
    Tar("tar", setOf("cbt", "tar"));

    fun matchesExtension(extension: String): Boolean = extension in suffixes
}

fun isSupportedLocalComicFileName(fileName: String): Boolean =
    localArchiveFormatForFileName(fileName) != null

fun localArchiveFormatForFileName(fileName: String): LocalArchiveFormat? {
    val extension = fileName.substringAfterLast('.', missingDelimiterValue = "")
        .lowercase(Locale.ROOT)
    if (extension.isBlank()) return null
    return LocalArchiveFormat.values().firstOrNull { it.matchesExtension(extension) }
}

fun localComicTitleFromFileName(fileName: String): String {
    val extension = fileName.substringAfterLast('.', missingDelimiterValue = "")
        .lowercase(Locale.ROOT)
    val format = LocalArchiveFormat.values().firstOrNull { it.matchesExtension(extension) }
    return if (format == null) fileName else fileName.dropLast(extension.length + 1)
}
