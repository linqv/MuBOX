package com.example.comicdav.video.proxy

import com.example.comicdav.network.WebDavClient

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

internal data class RegisteredVideoStream(
    val request: VideoStreamRequest,
    val openClient: suspend () -> WebDavClient?,
)
