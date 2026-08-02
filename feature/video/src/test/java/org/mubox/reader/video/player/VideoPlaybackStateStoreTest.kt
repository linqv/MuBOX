package org.mubox.reader.video.player

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class VideoPlaybackStateStoreTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @After
    fun tearDown() {
        scope.cancel()
    }

    @Test
    fun storesAndLoadsPlaybackPositionByStableKey() = runTest {
        val store = store("positions.preferences_pb")

        store.savePosition(playbackKey = "local|movie", positionMillis = 123_000L, durationMillis = 600_000L)

        assertEquals(123_000L, store.loadPosition("local|movie"))
        assertEquals(0L, store.loadPosition("local|other"))
    }

    @Test
    fun clearsPlaybackPositionNearEndOfVideo() = runTest {
        val store = store("near_end.preferences_pb")
        store.savePosition(playbackKey = "webdav|movie", positionMillis = 123_000L, durationMillis = 600_000L)

        store.savePosition(playbackKey = "webdav|movie", positionMillis = 599_500L, durationMillis = 600_000L)

        assertEquals(0L, store.loadPosition("webdav|movie"))
    }

    @Test
    fun clearAllRemovesEveryResumePosition() = runTest {
        val store = store("clear_all.preferences_pb")
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

    private fun store(fileName: String): VideoPlaybackStateStore =
        VideoPlaybackStateStore(
            PreferenceDataStoreFactory.create(scope = scope) {
                File(temporaryFolder.root, fileName)
            },
        )
}
