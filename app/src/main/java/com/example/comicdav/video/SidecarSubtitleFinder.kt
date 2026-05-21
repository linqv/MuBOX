package com.example.comicdav.video

import java.util.Locale

fun <T> findSidecarSubtitles(
    videoFileName: String,
    candidates: Iterable<T>,
    nameOf: (T) -> String,
    isDirectoryOf: (T) -> Boolean,
): List<T> {
    val videoStem = videoFileName.fileStem().lowercase(Locale.ROOT)
    if (videoStem.isBlank()) return emptyList()

    return candidates
        .filter { candidate ->
            val name = nameOf(candidate)
            !isDirectoryOf(candidate) &&
                mediaKindForFileName(name) == MediaKind.Subtitle &&
                name.isSidecarSubtitleFor(videoStem)
        }
        .sortedWith(
            compareBy<T> { nameOf(it).fileStem().lowercase(Locale.ROOT) != videoStem }
                .thenBy { nameOf(it).lowercase(Locale.ROOT) },
        )
}

private fun String.isSidecarSubtitleFor(videoStem: String): Boolean {
    val subtitleStem = fileStem().lowercase(Locale.ROOT)
    if (subtitleStem == videoStem) return true
    if (!subtitleStem.startsWith(videoStem)) return false
    val separator = subtitleStem.getOrNull(videoStem.length) ?: return false
    return separator in SidecarSuffixSeparators
}

private fun String.fileStem(): String =
    substringBeforeLast('.', missingDelimiterValue = this)

private val SidecarSuffixSeparators = setOf('.', '_', '[', '(')
