package org.mubox.reader.core.model.cache

data class ComicCacheAnalysis(
    val remoteDownloadsBytes: Long = 0,
    val remoteIndexBytes: Long = 0,
    val readerPagesBytes: Long = 0,
    val transientReaderPagesBytes: Long = 0,
    val libraryCoversBytes: Long = 0,
    val videoThumbnailsBytes: Long = 0,
    val historyThumbnailsBytes: Long = 0,
    val videoSubtitlesBytes: Long = 0,
    val codeCacheBytes: Long = 0,
    val externalCacheBytes: Long = 0,
    val otherBytes: Long = 0,
) {
    val totalBytes: Long
        get() = remoteDownloadsBytes +
            remoteIndexBytes +
            readerPagesBytes +
            transientReaderPagesBytes +
            libraryCoversBytes +
            videoThumbnailsBytes +
            historyThumbnailsBytes +
            videoSubtitlesBytes +
            codeCacheBytes +
            externalCacheBytes +
            otherBytes
}

data class CacheClearResult(
    val filesDeleted: Int,
    val bytesDeleted: Long,
)

enum class ComicCacheCategory {
    REMOTE_DOWNLOADS,
    REMOTE_INDEX,
    READER_PAGES,
    TRANSIENT_READER_PAGES,
    LIBRARY_COVERS,
    VIDEO_THUMBNAILS,
    HISTORY_THUMBNAILS,
    VIDEO_SUBTITLES,
    CODE_CACHE,
    EXTERNAL_CACHE,
    OTHER,
}
