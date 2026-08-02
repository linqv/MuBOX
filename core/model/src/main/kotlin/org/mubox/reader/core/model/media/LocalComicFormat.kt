package org.mubox.reader.core.model.media

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
    val extension = fileName.mediaExtension()
    if (extension.isBlank()) return null
    return LocalArchiveFormat.entries.firstOrNull { it.matchesExtension(extension) }
}

fun localDocumentFormatForFileName(fileName: String): LocalDocumentFormat? {
    val extension = fileName.mediaExtension()
    if (extension.isBlank()) return null
    return LocalDocumentFormat.entries.firstOrNull { it.matchesExtension(extension) }
}

fun localComicTitleFromFileName(fileName: String): String {
    val extension = fileName.mediaExtension()
    if (extension.isBlank()) return fileName
    val isSupported = LocalArchiveFormat.entries.any { it.matchesExtension(extension) } ||
        LocalDocumentFormat.entries.any { it.matchesExtension(extension) }
    return if (isSupported) fileName.dropLast(extension.length + 1) else fileName
}

internal fun String.mediaExtension(): String =
    substringAfterLast('.', missingDelimiterValue = "")
        .lowercase(Locale.ROOT)
