package com.example.comicdav.video.proxy

data class VideoStreamRequest(
    val streamId: String,
    val accountId: String,
    val remotePath: String,
    val displayName: String,
    val size: Long?,
    val etag: String?,
    val lastModified: Long?,
    val mimeType: String?,
)
