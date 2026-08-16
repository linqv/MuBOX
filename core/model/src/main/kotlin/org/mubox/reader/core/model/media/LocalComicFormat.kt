package org.mubox.reader.core.model.media

import java.util.Locale

private val supportedLocalComicSuffixes = setOf("cbz", "zip")

fun isSupportedLocalComicFileName(fileName: String): Boolean =
    fileName.mediaExtension() in supportedLocalComicSuffixes

fun localComicTitleFromFileName(fileName: String): String {
    val extension = fileName.mediaExtension()
    if (extension.isBlank()) return fileName
    return if (extension in supportedLocalComicSuffixes) fileName.dropLast(extension.length + 1) else fileName
}

internal fun String.mediaExtension(): String =
    substringAfterLast('.', missingDelimiterValue = "")
        .lowercase(Locale.ROOT)
