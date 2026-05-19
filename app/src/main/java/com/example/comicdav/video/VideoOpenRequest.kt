package com.example.comicdav.video

data class LocalVideoOpenRequest(
    val uri: String,
    val displayName: String,
    val size: Long?,
    val lastModified: Long?,
)

data class WebDavVideoOpenRequest(
    val accountId: String,
    val remotePath: String,
    val displayName: String,
    val size: Long?,
    val etag: String?,
    val lastModified: Long?,
    val mimeType: String?,
)
