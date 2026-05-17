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

enum class LocalDocumentFormat(
    val displayName: String,
    private val suffixes: Set<String>,
) {
    Pdf("PDF", setOf("pdf")),
    Epub("EPUB", setOf("epub")),
    Mobi("MOBI", setOf("mobi")),
    Azw3("AZW3", setOf("azw3"));

    fun matchesExtension(extension: String): Boolean = extension in suffixes
}

fun isSupportedLocalComicFileName(fileName: String): Boolean =
    localArchiveFormatForFileName(fileName) != null || localDocumentFormatForFileName(fileName) != null

fun localArchiveFormatForFileName(fileName: String): LocalArchiveFormat? {
    val extension = localFileExtension(fileName)
    if (extension.isBlank()) return null
    return LocalArchiveFormat.values().firstOrNull { it.matchesExtension(extension) }
}

fun localDocumentFormatForFileName(fileName: String): LocalDocumentFormat? {
    val extension = localFileExtension(fileName)
    if (extension.isBlank()) return null
    return LocalDocumentFormat.values().firstOrNull { it.matchesExtension(extension) }
}

fun localComicTitleFromFileName(fileName: String): String {
    val extension = localFileExtension(fileName)
    if (extension.isBlank()) return fileName
    val isSupported = LocalArchiveFormat.values().any { it.matchesExtension(extension) } ||
        LocalDocumentFormat.values().any { it.matchesExtension(extension) }
    return if (isSupported) fileName.dropLast(extension.length + 1) else fileName
}

private fun localFileExtension(fileName: String): String =
    fileName.substringAfterLast('.', missingDelimiterValue = "")
        .lowercase(Locale.ROOT)
