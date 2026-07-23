package com.example.comicdav.video.player

import com.example.comicdav.core.model.media.LocalVideoOpenRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VideoPlayerRestorationTest {
    @Test
    fun `saved episode index overrides the queue launch index`() {
        val first = localEpisode("content://video/1", "第 1 集")
        val second = localEpisode("content://video/2", "第 2 集")
        val queue = VideoEpisodeQueue(episodes = listOf(first, second), currentIndex = 0)

        val restored = restoredVideoEpisodeSelection(queue, savedEpisodeIndex = 1)

        assertEquals(1, restored?.index)
        assertEquals(second.playbackKey, restored?.episode?.playbackKey)
        assertEquals("第 2 集", restored?.episode?.toPlayerMediaContext()?.displayName)
        assertEquals("content://video/2", restored?.episode?.toPlayerMediaContext()?.remotePath)
    }

    @Test
    fun `fresh launch and stale saved indexes do not synthesize a restored episode`() {
        val queue = VideoEpisodeQueue(episodes = listOf(localEpisode("content://video/1", "第 1 集")))

        assertNull(restoredVideoEpisodeSelection(queue, savedEpisodeIndex = null))
        assertNull(restoredVideoEpisodeSelection(queue, savedEpisodeIndex = 9))
        assertNull(restoredVideoEpisodeSelection(episodeQueue = null, savedEpisodeIndex = 0))
    }

    private fun localEpisode(uri: String, displayName: String): VideoEpisode =
        VideoEpisode.local(
            LocalVideoOpenRequest(
                uri = uri,
                displayName = displayName,
                size = null,
                lastModified = null,
            ),
        )
}
