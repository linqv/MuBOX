package org.mubox.reader.core.model.media

data class LocalVideoOpenRequest(
    val uri: String,
    val displayName: String,
    val size: Long?,
    val lastModified: Long?,
    val subtitles: List<VideoSubtitleOpenRequest> = emptyList(),
)

data class WebDavVideoOpenRequest(
    val accountId: String,
    val remotePath: String,
    val displayName: String,
    val size: Long?,
    val etag: String?,
    val lastModified: Long?,
    val mimeType: String?,
    val subtitles: List<WebDavSubtitleOpenRequest> = emptyList(),
)

data class VideoSubtitleOpenRequest(
    val uri: String,
    val displayName: String,
)

data class WebDavSubtitleOpenRequest(
    val remotePath: String,
    val displayName: String,
    val size: Long?,
    val etag: String?,
    val lastModified: Long?,
    val mimeType: String?,
)
