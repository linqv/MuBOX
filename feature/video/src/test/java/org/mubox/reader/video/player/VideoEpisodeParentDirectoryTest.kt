package org.mubox.reader.video.player

import org.mubox.reader.core.model.media.LocalVideoOpenRequest
import org.mubox.reader.core.model.media.WebDavVideoOpenRequest
import org.junit.Assert.assertEquals
import org.junit.Test

class VideoEpisodeParentDirectoryTest {
    @Test
    fun localDocumentUriResolvesDecodedParentDirectoryName() {
        val queue = VideoEpisodeQueue(
            episodes = listOf(
                VideoEpisode.local(
                    LocalVideoOpenRequest(
                        uri = "content://media.documents/tree/primary%3AMovies/document/" +
                            "primary%3AMovies%2FSeries%2FSeason%2001%2FEpisode%2001.mkv",
                        displayName = "Episode 01.mkv",
                        size = null,
                        lastModified = null,
                    ),
                ),
            ),
        )

        assertEquals("Season 01", queue.parentDirectoryName(currentEpisodeIndex = 0))
    }

    @Test
    fun localDocumentRootResolvesVolumeDirectoryName() {
        val queue = VideoEpisodeQueue(
            episodes = listOf(
                VideoEpisode.local(
                    LocalVideoOpenRequest(
                        uri = "content://media.documents/tree/primary%3AMovies/document/" +
                            "primary%3AMovies%2FEpisode.mkv",
                        displayName = "Episode.mkv",
                        size = null,
                        lastModified = null,
                    ),
                ),
            ),
        )

        assertEquals("Movies", queue.parentDirectoryName(currentEpisodeIndex = 0))
    }

    @Test
    fun webDavPathResolvesDecodedParentDirectoryName() {
        val queue = VideoEpisodeQueue(
            episodes = listOf(
                VideoEpisode.webDav(
                    WebDavVideoOpenRequest(
                        accountId = "account",
                        remotePath = "/Shows/Season%2001/Episode%2001.mkv",
                        displayName = "Episode 01.mkv",
                        size = null,
                        etag = null,
                        lastModified = null,
                        mimeType = "video/x-matroska",
                    ),
                ),
            ),
        )

        assertEquals("Season 01", queue.parentDirectoryName(currentEpisodeIndex = 0))
    }
}
