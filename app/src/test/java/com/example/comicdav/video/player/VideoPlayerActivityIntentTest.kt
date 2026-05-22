package com.example.comicdav.video.player

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.comicdav.video.LocalVideoOpenRequest
import com.example.comicdav.video.WebDavVideoOpenRequest
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class VideoPlayerActivityIntentTest {
    @Test
    fun localIntentCarriesLocalVideoRequestExtras() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val request = LocalVideoOpenRequest(
            uri = "content://media/external/video/42",
            displayName = "Episode 01.mkv",
            size = 1024L,
            lastModified = 12345L,
        )

        val intent = VideoPlayerActivity.localIntent(context, request)

        assertEquals(VideoPlayerActivity::class.java.name, intent.component?.className)
        assertEquals("content://media/external/video/42", intent.getStringExtra(VideoPlayerActivity.EXTRA_URI))
        assertEquals("Episode 01.mkv", intent.getStringExtra(VideoPlayerActivity.EXTRA_DISPLAY_NAME))
        assertEquals(1024L, intent.getLongExtra(VideoPlayerActivity.EXTRA_SIZE, -1L))
        assertEquals(12345L, intent.getLongExtra(VideoPlayerActivity.EXTRA_LAST_MODIFIED, -1L))
        assertEquals(VideoPlayerActivity.SOURCE_LOCAL, intent.getStringExtra(VideoPlayerActivity.EXTRA_SOURCE))
    }

    @Test
    fun localIntentCarriesPlaybackQueueExtras() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val request = LocalVideoOpenRequest(
            uri = "content://media/external/video/42",
            displayName = "Episode 02.mkv",
            size = 2048L,
            lastModified = 200L,
        )
        val queue = VideoPlaybackQueue(
            items = listOf(
                VideoQueueItem("k1", "Episode 01.mkv", "content://1", VideoQueueSource.LOCAL),
                VideoQueueItem("k2", "Episode 02.mkv", "content://2", VideoQueueSource.LOCAL),
            ),
            currentIndex = 1,
        )

        val intent = VideoPlayerActivity.localIntent(context, request, queue = queue)

        assertEquals(arrayListOf("k1", "k2"), intent.getStringArrayListExtra(VideoPlayerActivity.EXTRA_QUEUE_KEYS))
        assertEquals(arrayListOf("Episode 01.mkv", "Episode 02.mkv"), intent.getStringArrayListExtra(VideoPlayerActivity.EXTRA_QUEUE_NAMES))
        assertEquals(arrayListOf("content://1", "content://2"), intent.getStringArrayListExtra(VideoPlayerActivity.EXTRA_QUEUE_URIS))
        assertEquals("local", intent.getStringExtra(VideoPlayerActivity.EXTRA_QUEUE_SOURCE))
        assertEquals(1, intent.getIntExtra(VideoPlayerActivity.EXTRA_QUEUE_INDEX, -1))
    }

    @Test
    fun webDavIntentCarriesPlaybackQueueExtras() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val request = WebDavVideoOpenRequest(
            accountId = "account",
            remotePath = "/shows/02.mkv",
            displayName = "02.mkv",
            size = 2L,
            etag = "etag2",
            lastModified = 20L,
            mimeType = "video/x-matroska",
        )
        val queue = VideoPlaybackQueue(
            items = listOf(
                VideoQueueItem("wk1", "01.mkv", "/shows/01.mkv", VideoQueueSource.WEB_DAV),
                VideoQueueItem("wk2", "02.mkv", "/shows/02.mkv", VideoQueueSource.WEB_DAV),
            ),
            currentIndex = 1,
        )

        val intent = VideoPlayerActivity.webDavIntent(
            context = context,
            request = request,
            uri = "http://127.0.0.1:1234/stream/current",
            subtitleUrls = emptyList(),
            streamIds = listOf("current"),
            queue = queue,
        )

        assertEquals(arrayListOf("wk1", "wk2"), intent.getStringArrayListExtra(VideoPlayerActivity.EXTRA_QUEUE_KEYS))
        assertEquals(arrayListOf("01.mkv", "02.mkv"), intent.getStringArrayListExtra(VideoPlayerActivity.EXTRA_QUEUE_NAMES))
        assertEquals(arrayListOf("/shows/01.mkv", "/shows/02.mkv"), intent.getStringArrayListExtra(VideoPlayerActivity.EXTRA_QUEUE_URIS))
        assertEquals("webdav", intent.getStringExtra(VideoPlayerActivity.EXTRA_QUEUE_SOURCE))
        assertEquals(1, intent.getIntExtra(VideoPlayerActivity.EXTRA_QUEUE_INDEX, -1))
    }
}
