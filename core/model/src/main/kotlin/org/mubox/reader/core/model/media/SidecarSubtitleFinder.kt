package org.mubox.reader.core.model.media

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
    if (separator !in SidecarSuffixSeparators) return false
    return subtitleStem
        .drop(videoStem.length + 1)
        .isSupportedSidecarLanguageSuffix()
}

private fun String.fileStem(): String = substringBeforeLast('.', missingDelimiterValue = this)

private fun String.isSupportedSidecarLanguageSuffix(): Boolean {
    val normalized = trim { it in SidecarSuffixTrimChars }
    return normalized.isNotBlank() && SidecarLanguageSuffixPattern.matches(normalized)
}

private val SidecarSuffixSeparators = setOf('.', '_', '-', '[', '(')
private val SidecarSuffixTrimChars = setOf(' ', '.', '_', '-', '[', ']', '(', ')')
private val SidecarLanguageSuffixPattern = Regex("[a-z]{2,3}([._-][a-z0-9]{2,8}){0,2}")
