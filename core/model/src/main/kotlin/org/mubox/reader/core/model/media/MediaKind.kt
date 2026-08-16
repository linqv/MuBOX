package org.mubox.reader.core.model.media

import java.util.Locale

enum class MediaKind {
    Directory,
    Comic,
    Video,
    Audio,
    Subtitle,
    Unknown,
}

val MediaKind.isBrowsableInSources: Boolean
    get() = when (this) {
        MediaKind.Directory,
        MediaKind.Comic,
        MediaKind.Video,
        MediaKind.Subtitle,
        -> true
        MediaKind.Audio,
        MediaKind.Unknown,
        -> false
    }

fun mediaKindFor(name: String, isDirectory: Boolean, mimeType: String? = null): MediaKind {
    if (isDirectory) return MediaKind.Directory
    if (mimeType != null && mimeType != "application/octet-stream") {
        mediaKindForMimeType(mimeType)?.let { return it }
    }
    return mediaKindForFileName(name)
}

fun mediaKindForFileName(fileName: String): MediaKind {
    if (isSupportedLocalComicFileName(fileName)) return MediaKind.Comic
    val extension = fileName.mediaExtension()
    if (extension.isBlank()) return MediaKind.Unknown
    return when (extension) {
        in videoExtensions -> MediaKind.Video
        in audioExtensions -> MediaKind.Audio
        in subtitleExtensions -> MediaKind.Subtitle
        else -> MediaKind.Unknown
    }
}

private fun mediaKindForMimeType(mimeType: String): MediaKind? {
    val normalized = mimeType.substringBefore(';').trim().lowercase(Locale.ROOT)
    return when {
        normalized == "vnd.android.document/directory" -> MediaKind.Directory
        normalized.startsWith("video/") -> MediaKind.Video
        normalized.startsWith("audio/") -> MediaKind.Audio
        normalized in subtitleMimeTypes -> MediaKind.Subtitle
        normalized in comicMimeTypes -> MediaKind.Comic
        else -> null
    }
}

private val videoExtensions = setOf(
    "mp4", "mkv", "webm", "avi", "mov", "m4v", "wmv", "flv", "3gp", "3g2",
    "mpg", "mpeg", "ts", "mts", "m2ts", "vob", "ogv", "rm", "rmvb", "asf",
)

private val audioExtensions = setOf("mp3", "m4a", "aac", "flac", "wav", "ogg", "opus", "wma")
private val subtitleExtensions = setOf("srt", "ass", "ssa", "vtt", "sub")
private val subtitleMimeTypes = setOf(
    "application/x-subrip",
    "text/vtt",
    "text/x-ssa",
    "text/x-ass",
)
private val comicMimeTypes = setOf(
    "application/zip",
    "application/x-cbz",
)
