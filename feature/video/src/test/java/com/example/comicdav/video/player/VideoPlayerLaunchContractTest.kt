package com.example.comicdav.video.player

import android.content.Context
import android.content.Intent
import android.os.Parcel
import androidx.test.core.app.ApplicationProvider
import com.example.comicdav.core.model.media.LocalVideoOpenRequest
import com.example.comicdav.core.model.media.VideoSubtitleOpenRequest
import com.example.comicdav.core.model.media.WebDavSubtitleOpenRequest
import com.example.comicdav.core.model.media.WebDavVideoOpenRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class VideoPlayerLaunchContractTest {
    private val context: Context
        get() = ApplicationProvider.getApplicationContext()

    @Before
    fun clearEpisodeQueues() {
        VideoEpisodeQueueStore.clearForTests(context.noBackupFilesDir)
    }

    @Test
    fun localLaunchRoundTripsThroughContractReader() {
        val request = LocalVideoOpenRequest(
            uri = "content://videos/episode-1",
            displayName = "episode-1.mkv",
            size = 100L,
            lastModified = 200L,
            subtitles = listOf(
                VideoSubtitleOpenRequest(
                    uri = "content://subtitles/episode-1",
                    displayName = "episode-1.ass",
                ),
            ),
        )
        val queue = VideoEpisodeQueue(listOf(VideoEpisode.local(request)))
        val options = VideoPlayerOptions(resumeEnabled = false, controlsAutoHideMillis = 8_000)

        val intent = VideoPlayerLaunchContract.localIntent(
            context = context,
            request = request,
            options = options,
            episodeQueue = queue,
        )
        val arguments = VideoPlayerLaunchContract.read(context, intent)

        assertEquals(VideoPlayerActivity::class.java.name, intent.component?.className)
        assertTrue(intent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
        assertEquals(request.uri, arguments.uri)
        assertEquals(request.displayName, arguments.displayName)
        assertEquals(VideoPlayerLaunchContract.SOURCE_LOCAL, arguments.source)
        assertEquals(request.uri, arguments.remotePath)
        assertEquals(request.subtitles, arguments.subtitles)
        assertEquals(request.let { localVideoPlaybackKey(it.uri, it.size, it.lastModified) }, arguments.playbackKey)
        assertEquals(options, arguments.options)
        assertEquals(queue.episodes, arguments.episodeQueue?.episodes)
        assertEquals(queue.currentIndex, arguments.episodeQueue?.currentIndex)
        assertTrue(!arguments.isWebDav)
    }

    @Test
    fun episodeQueueSurvivesRepeatedReadsAndIntentParcelRecreation() {
        val first = LocalVideoOpenRequest(
            uri = "content://videos/episode-1",
            displayName = "episode-1.mkv",
            size = 100L,
            lastModified = 200L,
        )
        val second = first.copy(
            uri = "content://videos/episode-2",
            displayName = "episode-2.mkv",
            size = 300L,
            lastModified = 400L,
        )
        val queue = VideoEpisodeQueue(
            episodes = listOf(VideoEpisode.local(first), VideoEpisode.local(second)),
            currentIndex = 1,
        )
        val intent = VideoPlayerLaunchContract.localIntent(context, second, episodeQueue = queue)
        val token = intent.getStringExtra(VideoPlayerLaunchContract.EXTRA_EPISODE_QUEUE)

        val firstRead = VideoPlayerLaunchContract.read(context, intent).episodeQueue
        val secondRead = VideoPlayerLaunchContract.read(context, intent).episodeQueue
        val parcel = Parcel.obtain()
        val recreatedIntent = try {
            intent.writeToParcel(parcel, 0)
            parcel.setDataPosition(0)
            Intent.CREATOR.createFromParcel(parcel)
        } finally {
            parcel.recycle()
        }
        val recreatedRead = VideoPlayerLaunchContract.read(context, recreatedIntent).episodeQueue

        assertNotNull(token)
        assertEquals(queue.episodes, firstRead?.episodes)
        assertEquals(1, firstRead?.currentIndex)
        assertEquals(firstRead?.episodes, secondRead?.episodes)
        assertEquals(firstRead?.episodes, recreatedRead?.episodes)
        assertEquals(firstRead?.currentIndex, recreatedRead?.currentIndex)
    }

    @Test
    fun persistentEpisodeQueueStorePrunesOldLaunchPayloads() {
        repeat(24) { index ->
            val request = LocalVideoOpenRequest(
                uri = "content://videos/$index",
                displayName = "episode-$index.mkv",
                size = index.toLong(),
                lastModified = index.toLong(),
            )
            VideoPlayerLaunchContract.localIntent(
                context = context,
                request = request,
                episodeQueue = VideoEpisodeQueue(listOf(VideoEpisode.local(request))),
            )
        }

        assertEquals(16, VideoEpisodeQueueStore.activePayloadCountForTests(context.noBackupFilesDir))
    }

    @Test
    fun oversizedEpisodeQueuePayloadFailsBeforePublishingToken() {
        val largeSegment = "x".repeat(60_000)
        val episodes = (0 until 18).map { index ->
            VideoEpisode.local(
                LocalVideoOpenRequest(
                    uri = "content://videos/$index/$largeSegment",
                    displayName = "episode-$index.mkv",
                    size = index.toLong(),
                    lastModified = index.toLong(),
                ),
            )
        }
        val queue = VideoEpisodeQueue(episodes)

        val error = runCatching {
            VideoPlayerLaunchContract.localIntent(
                context = context,
                request = requireNotNull(queue.currentEpisode?.localRequest),
                episodeQueue = queue,
            )
        }.exceptionOrNull()

        assertTrue(error is IllegalArgumentException)
        assertEquals(0, VideoEpisodeQueueStore.activePayloadCountForTests(context.noBackupFilesDir))
    }

    @Test
    fun readerRejectsUntrustedPathAndDeletesCorruptOwnedPayload() {
        val request = LocalVideoOpenRequest(
            uri = "content://videos/episode",
            displayName = "episode.mkv",
            size = 1L,
            lastModified = 2L,
        )
        val untrusted = Intent().putExtra(
            VideoPlayerLaunchContract.EXTRA_EPISODE_QUEUE,
            "../queue-forged.bin",
        )
        assertNull(VideoPlayerLaunchContract.read(context, untrusted).episodeQueue)

        val intent = VideoPlayerLaunchContract.localIntent(
            context = context,
            request = request,
            episodeQueue = VideoEpisodeQueue(listOf(VideoEpisode.local(request))),
        )
        val payloadFile = requireNotNull(
            VideoEpisodeQueueStore.payloadFileForTests(
                context.noBackupFilesDir,
                intent.getStringExtra(VideoPlayerLaunchContract.EXTRA_EPISODE_QUEUE),
            ),
        )
        payloadFile.writeBytes(byteArrayOf(1, 2, 3, 4))

        assertNull(VideoPlayerLaunchContract.read(context, intent).episodeQueue)
        assertFalse(payloadFile.exists())
    }

    @Test
    fun webDavLaunchReaderRestoresProxySubtitlesAndStreamOwnership() {
        val request = WebDavVideoOpenRequest(
            accountId = "account-1",
            remotePath = "/shows/episode-2.mkv",
            displayName = "episode-2.mkv",
            size = 300L,
            etag = "etag-2",
            lastModified = 400L,
            mimeType = "video/x-matroska",
            subtitles = listOf(
                WebDavSubtitleOpenRequest(
                    remotePath = "/shows/episode-2.zh.srt",
                    displayName = "episode-2.zh.srt",
                    size = 50L,
                    etag = null,
                    lastModified = null,
                    mimeType = "application/x-subrip",
                ),
            ),
        )

        val arguments = VideoPlayerLaunchContract.read(
            context,
            VideoPlayerLaunchContract.webDavIntent(
                context = context,
                request = request,
                uri = "http://127.0.0.1/video-stream",
                subtitleUrls = listOf("http://127.0.0.1/subtitle-stream"),
                streamIds = listOf("video-stream", "subtitle-stream"),
                episodeQueue = VideoEpisodeQueue(listOf(VideoEpisode.webDav(request))),
            ),
        )

        assertTrue(arguments.isWebDav)
        assertEquals(VideoPlayerLaunchContract.SOURCE_WEB_DAV, arguments.source)
        assertEquals(request.remotePath, arguments.remotePath)
        assertEquals(
            listOf(VideoSubtitleOpenRequest("http://127.0.0.1/subtitle-stream", "episode-2.zh.srt")),
            arguments.subtitles,
        )
        assertEquals(listOf("video-stream", "subtitle-stream"), arguments.webDavStreamIds)
        assertEquals(request, arguments.episodeQueue?.currentEpisode?.webDavRequest)
    }

    @Test
    fun webDavLaunchPersistsSingleEpisodeWhenCallerOmitsQueue() {
        val request = WebDavVideoOpenRequest(
            accountId = "account-1",
            remotePath = "/movies/movie.mkv",
            displayName = "movie.mkv",
            size = 300L,
            etag = "etag",
            lastModified = 400L,
            mimeType = "video/x-matroska",
        )

        val intent = VideoPlayerLaunchContract.webDavIntent(
            context = context,
            request = request,
            uri = "http://127.0.0.1/video-stream",
            subtitleUrls = emptyList(),
            streamIds = listOf("video-stream"),
        )

        val restoredQueue = VideoPlayerLaunchContract.read(context, intent).episodeQueue
        assertEquals(listOf(VideoEpisode.webDav(request)), restoredQueue?.episodes)
        assertEquals(0, restoredQueue?.currentIndex)
    }

    @Test
    fun scopedWebDavLaunchCarriesRequestWithoutProcessGlobalProxyUrl() {
        val request = WebDavVideoOpenRequest(
            accountId = "account-1",
            remotePath = "/movies/movie.mkv",
            displayName = "movie.mkv",
            size = 300L,
            etag = "etag",
            lastModified = 400L,
            mimeType = "video/x-matroska",
        )

        val arguments = VideoPlayerLaunchContract.read(
            context,
            VideoPlayerLaunchContract.webDavIntent(
                context = context,
                request = request,
            ),
        )

        assertNull(arguments.uri)
        assertTrue(arguments.webDavStreamIds.isEmpty())
        assertTrue(arguments.subtitles.isEmpty())
        assertEquals(request, arguments.episodeQueue?.currentEpisode?.webDavRequest)
    }

    @Test
    fun activityCompanionKeepsExistingLaunchApiAsContractAliases() {
        assertEquals(VideoPlayerLaunchContract.EXTRA_SOURCE, VideoPlayerActivity.EXTRA_SOURCE)
        assertEquals(VideoPlayerLaunchContract.EXTRA_URI, VideoPlayerActivity.EXTRA_URI)
        assertEquals(VideoPlayerLaunchContract.EXTRA_PLAYER_OPTIONS, VideoPlayerActivity.EXTRA_PLAYER_OPTIONS)
        assertEquals(VideoPlayerLaunchContract.EXTRA_EPISODE_QUEUE, VideoPlayerLaunchContract.EXTRA_EPISODE_QUEUE_ID)
        assertEquals(VideoPlayerLaunchContract.EXTRA_EPISODE_QUEUE_ID, VideoPlayerActivity.EXTRA_EPISODE_QUEUE_ID)
        assertEquals(VideoPlayerLaunchContract.SOURCE_LOCAL, VideoPlayerActivity.SOURCE_LOCAL)

        val request = LocalVideoOpenRequest(
            uri = "content://videos/compatibility",
            displayName = "compatibility.mkv",
            size = null,
            lastModified = null,
        )
        val delegated = VideoPlayerActivity.localIntent(context, request)
        val direct = VideoPlayerLaunchContract.localIntent(context, request)

        assertEquals(direct.component, delegated.component)
        assertEquals(
            direct.getStringExtra(VideoPlayerLaunchContract.EXTRA_PLAYBACK_KEY),
            delegated.getStringExtra(VideoPlayerLaunchContract.EXTRA_PLAYBACK_KEY),
        )
        assertEquals(direct.videoPlayerOptions(), delegated.videoPlayerOptions())
    }
}
