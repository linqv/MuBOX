package org.mubox.reader.video.player

import org.mubox.reader.core.ports.PlaybackPositionGateway
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class VideoPlaybackStateStoreTest {
    @Test
    fun storesAndLoadsPlaybackPositionByStableKey() = runTest {
        val store = store()

        store.savePosition(playbackKey = "local|movie", positionMillis = 123_000L, durationMillis = 600_000L)

        assertEquals(123_000L, store.loadPosition("local|movie"))
        assertEquals(0L, store.loadPosition("local|other"))
    }

    @Test
    fun clearsPlaybackPositionNearEndOfVideo() = runTest {
        val store = store()
        store.savePosition(playbackKey = "webdav|movie", positionMillis = 123_000L, durationMillis = 600_000L)

        store.savePosition(playbackKey = "webdav|movie", positionMillis = 599_500L, durationMillis = 600_000L)

        assertEquals(0L, store.loadPosition("webdav|movie"))
    }

    @Test
    fun clearAllRemovesEveryResumePosition() = runTest {
        val store = store()
        store.savePosition("video-1", 10_000L, 60_000L)
        store.savePosition("video-2", 20_000L, 60_000L)

        store.clearAll()

        assertEquals(0L, store.loadPosition("video-1"))
        assertEquals(0L, store.loadPosition("video-2"))
    }

    @Test
    fun buildsDifferentStableKeysForDifferentVideoSources() {
        val localKey = localVideoPlaybackKey(
            uri = "content://video/1",
            size = 10L,
            lastModified = 20L,
        )
        val webDavKey = webDavVideoPlaybackKey(
            accountId = "account",
            remotePath = "/video/1.mkv",
            size = 10L,
            etag = "etag",
            lastModified = 20L,
        )

        assertEquals("local|content://video/1|10|20", localKey)
        assertEquals("webdav|account|/video/1.mkv|10|etag|20", webDavKey)
    }

    private fun store(): VideoPlaybackStateStore = VideoPlaybackStateStore(InMemoryPlaybackPositions())
}

private class InMemoryPlaybackPositions : PlaybackPositionGateway {
    private val positions = mutableMapOf<String, Long>()

    override suspend fun loadPosition(playbackKey: String): Long = positions[playbackKey] ?: 0L

    override suspend fun savePosition(playbackKey: String, positionMillis: Long) {
        positions[playbackKey] = positionMillis
    }

    override suspend fun deletePosition(playbackKey: String) {
        positions.remove(playbackKey)
    }

    override suspend fun clear() {
        positions.clear()
    }
}
