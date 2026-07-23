package com.example.comicdav.video.player

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.comicdav.core.model.media.MediaEntry
import com.example.comicdav.core.remote.WebDavItem
import com.example.comicdav.core.model.media.LocalVideoOpenRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class MainActivityVideoEpisodeIntegrationTest {
    @Test
    fun localAndWebDavDirectoryVideosBuildEpisodeQueues() {
        val localEntries = listOf(
            MediaEntry("Show E01.mkv", "content://videos/1", isDirectory = false),
            MediaEntry("Show E01.ass", "content://videos/1-sub", isDirectory = false),
            MediaEntry("Show E02.mkv", "content://videos/2", isDirectory = false),
        )
        val localQueue = buildLocalDirectoryEpisodeQueue(localEntries, localEntries.last())
        val webDavItems = listOf(
            WebDavItem("Show E01.mkv", "/show/1.mkv", false, 10L, "one", 1L),
            WebDavItem("Show E01.ass", "/show/1.ass", false, 2L, "sub", 1L),
            WebDavItem("Show E02.mkv", "/show/2.mkv", false, 20L, "two", 2L),
        )
        val webDavQueue = buildWebDavDirectoryEpisodeQueue("account-1", webDavItems, webDavItems.last())

        assertEquals(1, localQueue?.currentIndex)
        assertEquals(listOf("Show E01.mkv", "Show E02.mkv"), localQueue?.episodes?.map { it.displayName })
        assertEquals(listOf("Show E01.ass"), localQueue?.episodes?.first()?.localRequest?.subtitles?.map { it.displayName })
        assertEquals(1, webDavQueue?.currentIndex)
        assertEquals(listOf("Show E01.mkv", "Show E02.mkv"), webDavQueue?.episodes?.map { it.displayName })
        assertEquals(listOf("Show E01.ass"), webDavQueue?.episodes?.first()?.webDavRequest?.subtitles?.map { it.displayName })
    }

    @Test
    fun playerActivityCarriesNavigableEpisodeQueueOutsideBinderPayload() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        VideoEpisodeQueueStore.clearForTests(context.noBackupFilesDir)
        val first = LocalVideoOpenRequest(
            uri = "content://videos/episode-1",
            displayName = "Episode 1",
            size = 1L,
            lastModified = 10L,
        )
        val second = first.copy(
            uri = "content://videos/episode-2",
            displayName = "Episode 2",
            size = 2L,
            lastModified = 20L,
        )
        val queue = VideoEpisodeQueue(
            episodes = listOf(VideoEpisode.local(first), VideoEpisode.local(second)),
            currentIndex = 1,
        )

        val intent = VideoPlayerActivity.localIntent(context, second, episodeQueue = queue)
        val queueId = intent.getStringExtra(VideoPlayerActivity.EXTRA_EPISODE_QUEUE_ID)
        val restored = VideoPlayerLaunchContract.read(context, intent).episodeQueue

        assertNotNull(queueId)
        assertEquals(1, restored?.currentIndex)
        assertTrue(restored?.hasPrevious == true)
        assertTrue(restored?.hasNext == false)
        assertEquals("Episode 2", restored?.currentEpisode?.displayName)
    }

}
