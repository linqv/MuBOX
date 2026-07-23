package com.example.comicdav.video.player

import com.example.comicdav.core.model.history.WatchMediaType
import com.example.comicdav.core.model.history.WatchSourceType
import com.example.comicdav.core.model.media.LocalVideoOpenRequest
import com.example.comicdav.core.model.media.WebDavVideoOpenRequest
import org.junit.Assert.assertEquals
import org.junit.Test

class VideoWatchHistoryMetadataTest {
    @Test
    fun localEpisodePreservesResumableHistorySource() {
        val request = LocalVideoOpenRequest(
            uri = "content://videos/episode-1",
            displayName = "Episode 1",
            size = 42L,
            lastModified = 7L,
        )

        val metadata = VideoEpisode.local(request).toWatchHistoryMetadata()

        assertEquals(WatchMediaType.VIDEO, metadata.mediaType)
        assertEquals(WatchSourceType.LOCAL, metadata.sourceType)
        assertEquals(request.uri, metadata.sourceLocator)
        assertEquals(request.displayName, metadata.title)
    }

    @Test
    fun webDavEpisodePreservesAccountAndValidator() {
        val request = WebDavVideoOpenRequest(
            accountId = "account",
            remotePath = "/shows/episode-2.mkv",
            displayName = "Episode 2",
            size = 84L,
            etag = "etag-2",
            lastModified = 9L,
            mimeType = null,
        )

        val metadata = VideoEpisode.webDav(request).toWatchHistoryMetadata()

        assertEquals(WatchSourceType.WEB_DAV, metadata.sourceType)
        assertEquals(request.accountId, metadata.accountId)
        assertEquals(request.remotePath, metadata.sourceLocator)
        assertEquals(request.etag, metadata.etag)
    }
}
