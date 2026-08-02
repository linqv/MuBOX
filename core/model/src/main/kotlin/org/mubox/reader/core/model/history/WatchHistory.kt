package org.mubox.reader.core.model.history

import java.net.URLDecoder
import java.nio.charset.StandardCharsets

enum class WatchMediaType {
    COMIC,
    VIDEO,
}

enum class WatchSourceType {
    LOCAL,
    WEB_DAV,
}

/**
 * A resumable item in the user's watch history.
 *
 * [progress] and [total] use pages for comics and milliseconds for videos.
 */
data class WatchHistoryEntry(
    val mediaKey: String,
    val mediaType: WatchMediaType,
    val title: String,
    val sourceType: WatchSourceType,
    val sourceLocator: String,
    val accountId: String? = null,
    val size: Long? = null,
    val etag: String? = null,
    val lastModified: Long? = null,
    val progress: Long,
    val total: Long,
    val lastWatchedAt: Long,
) {
    val displayTitle: String
        get() = decodePercentEncodedMediaTitle(title)

    val progressFraction: Float
        get() = if (total > 0L) {
            (progress.toDouble() / total.toDouble()).coerceIn(0.0, 1.0).toFloat()
        } else {
            0f
        }
}

data class WatchHistoryMetadata(
    val mediaKey: String,
    val mediaType: WatchMediaType,
    val title: String,
    val sourceType: WatchSourceType,
    val sourceLocator: String,
    val accountId: String? = null,
    val size: Long? = null,
    val etag: String? = null,
    val lastModified: Long? = null,
) {
    fun entry(
        progress: Long,
        total: Long,
        watchedAt: Long = System.currentTimeMillis(),
    ): WatchHistoryEntry =
        WatchHistoryEntry(
            mediaKey = mediaKey,
            mediaType = mediaType,
            title = decodePercentEncodedMediaTitle(title),
            sourceType = sourceType,
            sourceLocator = sourceLocator,
            accountId = accountId,
            size = size,
            etag = etag,
            lastModified = lastModified,
            progress = progress.coerceAtLeast(0L),
            total = total.coerceAtLeast(0L),
            lastWatchedAt = watchedAt,
        )
}

fun decodePercentEncodedMediaTitle(title: String): String {
    if ('%' !in title) return title
    return runCatching {
        URLDecoder.decode(
            title.replace("+", "%2B"),
            StandardCharsets.UTF_8.name(),
        )
    }.getOrDefault(title)
}
