package com.example.comicdav.video.player

import android.content.Context
import android.content.Intent
import android.os.Parcel
import androidx.test.core.app.ApplicationProvider
import com.example.comicdav.core.model.settings.Anime4KProfile
import com.example.comicdav.core.model.settings.GpuApiMode
import com.example.comicdav.core.model.settings.MpvProfileMode
import com.example.comicdav.core.model.settings.VideoBackgroundMode
import com.example.comicdav.core.model.settings.VideoDecoderMode
import com.example.comicdav.core.model.settings.VideoOutputMode
import com.example.comicdav.core.model.settings.VideoPlayerOrientationMode
import com.example.comicdav.core.model.media.LocalVideoOpenRequest
import com.example.comicdav.core.model.media.WebDavVideoOpenRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
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
        assertEquals(VideoPlayerOptions(), intent.videoPlayerOptions())
        assertEquals(
            Anime4KProfile.OFF.name,
            intent.getStringExtra(VideoPlayerActivity.EXTRA_ANIME4K_PROFILE),
        )
    }

    @Test
    fun localIntentCarriesConfiguredVideoBackendModes() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val request = LocalVideoOpenRequest(
            uri = "content://videos/movie.mkv",
            displayName = "movie.mkv",
            size = null,
            lastModified = null,
        )

        val options = customPlayerOptions()
        val intent = VideoPlayerActivity.localIntent(
            context = context,
            request = request,
            options = options,
        )

        assertEquals(options, intent.videoPlayerOptions())
        assertEquals(
            VideoOutputMode.GPU_NEXT.name,
            intent.getStringExtra(VideoPlayerActivity.EXTRA_VIDEO_OUTPUT_MODE),
        )
        assertEquals(
            GpuApiMode.VULKAN.name,
            intent.getStringExtra(VideoPlayerActivity.EXTRA_GPU_API_MODE),
        )
        assertEquals(
            VideoDecoderMode.HARDWARE_PLUS.name,
            intent.getStringExtra(VideoPlayerActivity.EXTRA_VIDEO_DECODER_MODE),
        )
        assertEquals(
            MpvProfileMode.LOW_LATENCY.name,
            intent.getStringExtra(VideoPlayerActivity.EXTRA_MPV_PROFILE_MODE),
        )
        assertEquals(
            8000,
            intent.getIntExtra(VideoPlayerActivity.EXTRA_CONTROLS_AUTO_HIDE_MILLIS, -1),
        )
        assertEquals(
            true,
            intent.getBooleanExtra(VideoPlayerActivity.EXTRA_PROXY_DEBUG_INFO_ENABLED, false),
        )
        assertEquals(
            Anime4KProfile.EXTREME.name,
            intent.getStringExtra(VideoPlayerActivity.EXTRA_ANIME4K_PROFILE),
        )
    }

    @Test
    fun playerOptionsRoundTripThroughParcelableWireFormat() {
        val options = customPlayerOptions()
        val parcel = Parcel.obtain()
        try {
            options.writeToParcel(parcel, 0)
            parcel.setDataPosition(0)

            assertEquals(options, VideoPlayerOptions.CREATOR.createFromParcel(parcel))
        } finally {
            parcel.recycle()
        }
    }

    @Test
    fun playerOptionsReaderFallsBackToLegacyScalarExtras() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val options = customPlayerOptions()
        val intent = VideoPlayerActivity.localIntent(
            context = context,
            request = LocalVideoOpenRequest("content://videos/legacy", "legacy.mkv", null, null),
            options = options,
        )
        intent.removeExtra(VideoPlayerActivity.EXTRA_PLAYER_OPTIONS)

        assertEquals(options, intent.videoPlayerOptions())
    }

    @Test
    fun oldAnime4KScalarExtrasMigrateToExtremeProfile() {
        val intent = Intent()
            .putExtra(VideoPlayerLaunchContract.EXTRA_ANIME4K_ENABLED, true)
            .putExtra(VideoPlayerLaunchContract.EXTRA_ANIME4K_MODE, "C_PLUS")
            .putExtra(VideoPlayerLaunchContract.EXTRA_ANIME4K_QUALITY, "HIGH")

        assertEquals(Anime4KProfile.EXTREME, intent.videoPlayerOptions().anime4kProfile)
    }

    @Test
    fun parcelableOptionsTakePrecedenceOverStaleLegacyExtras() {
        val options = customPlayerOptions()
        val intent = Intent()
            .putVideoPlayerOptions(options)
            .putExtra(VideoPlayerActivity.EXTRA_VIDEO_OUTPUT_MODE, VideoOutputMode.AUTO.name)

        assertEquals(options, intent.videoPlayerOptions())
    }

    @Test
    fun unknownLegacyEnumValuesUsePlayerDefaults() {
        val intent = Intent()
            .putExtra(VideoPlayerActivity.EXTRA_VIDEO_OUTPUT_MODE, "REMOVED_OUTPUT")
            .putExtra(VideoPlayerActivity.EXTRA_GPU_API_MODE, "REMOVED_GPU_API")
            .putExtra(VideoPlayerActivity.EXTRA_PLAYER_ORIENTATION_MODE, "REMOVED_ORIENTATION")

        assertEquals(VideoPlayerOptions(), intent.videoPlayerOptions())
    }

    @Test
    fun localIntentCarriesEpisodeQueue() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        VideoEpisodeQueueStore.clearForTests(context.noBackupFilesDir)
        val request = LocalVideoOpenRequest(
            uri = "content://media/external/video/42",
            displayName = "Episode 02.mkv",
            size = 2048L,
            lastModified = 200L,
        )

        val queue = VideoEpisodeQueue(
            episodes = listOf(
                VideoEpisode.local(
                    request.copy(
                        uri = "content://media/external/video/41",
                        displayName = "Episode 01.mkv",
                    ),
                ),
                VideoEpisode.local(request),
            ),
            currentIndex = 1,
        )
        val intent = VideoPlayerActivity.localIntent(context, request, episodeQueue = queue)

        val queueId = intent.getStringExtra(VideoPlayerActivity.EXTRA_EPISODE_QUEUE_ID)
        assertNotNull(queueId)
        val restored = requireNotNull(VideoPlayerLaunchContract.read(context, intent).episodeQueue)
        assertEquals(2, restored.episodes.size)
        assertEquals(1, restored.currentIndex)
    }

    @Test
    fun webDavIntentCarriesEpisodeQueue() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        VideoEpisodeQueueStore.clearForTests(context.noBackupFilesDir)
        val request = WebDavVideoOpenRequest(
            accountId = "account",
            remotePath = "/shows/02.mkv",
            displayName = "02.mkv",
            size = 2L,
            etag = "etag2",
            lastModified = 20L,
            mimeType = "video/x-matroska",
        )

        val queue = VideoEpisodeQueue(
            episodes = listOf(
                VideoEpisode.webDav(request.copy(remotePath = "/shows/01.mkv", displayName = "01.mkv")),
                VideoEpisode.webDav(request),
            ),
            currentIndex = 1,
        )
        val intent = VideoPlayerActivity.webDavIntent(
            context = context,
            request = request,
            uri = "http://127.0.0.1:1234/stream/current",
            subtitleUrls = emptyList(),
            streamIds = listOf("current"),
            options = customPlayerOptions(),
            episodeQueue = queue,
        )

        val queueId = intent.getStringExtra(VideoPlayerActivity.EXTRA_EPISODE_QUEUE_ID)
        assertNotNull(queueId)
        assertEquals(2, VideoPlayerLaunchContract.read(context, intent).episodeQueue?.episodes?.size)
        assertEquals(
            true,
            intent.getBooleanExtra(VideoPlayerActivity.EXTRA_PROXY_DEBUG_INFO_ENABLED, false),
        )
        assertEquals(
            Anime4KProfile.EXTREME.name,
            intent.getStringExtra(VideoPlayerActivity.EXTRA_ANIME4K_PROFILE),
        )
    }

    @Test
    fun largeEpisodeQueueStaysOutOfIntentBinderPayload() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        VideoEpisodeQueueStore.clearForTests(context.noBackupFilesDir)
        val episodes = (1..4_000).map { number ->
            VideoEpisode.local(
                LocalVideoOpenRequest(
                    uri = "content://videos/$number",
                    displayName = "Episode $number.mkv",
                    size = number.toLong(),
                    lastModified = number.toLong(),
                ),
            )
        }
        val queue = VideoEpisodeQueue(episodes = episodes)
        val intent = VideoPlayerActivity.localIntent(
            context = context,
            request = requireNotNull(queue.currentEpisode?.localRequest),
            episodeQueue = queue,
        )

        val queueId = intent.getStringExtra(VideoPlayerActivity.EXTRA_EPISODE_QUEUE_ID)
        assertNotNull(queueId)
        val parcel = Parcel.obtain()
        try {
            parcel.writeBundle(intent.extras)
            assertTrue("Intent extras should remain well below Binder limits", parcel.dataSize() < 64 * 1024)
        } finally {
            parcel.recycle()
        }
        assertEquals(4_000, VideoPlayerLaunchContract.read(context, intent).episodeQueue?.episodes?.size)
    }

    @Test
    fun episodeQueueClampsIndexAndRestoresCurrentEpisodeByStableKey() {
        val episodes = listOf(
            VideoEpisode.local(
                LocalVideoOpenRequest("content://videos/1", "第 1 集.mkv", 1L, 1L),
            ),
            VideoEpisode.webDav(
                WebDavVideoOpenRequest(
                    accountId = "account",
                    remotePath = "/shows/2.mkv",
                    displayName = "第 2 集.mkv",
                    size = 2L,
                    etag = "etag-2",
                    lastModified = 2L,
                    mimeType = "video/x-matroska",
                ),
            ),
            VideoEpisode.local(
                LocalVideoOpenRequest("content://videos/3", "第 3 集.mkv", 3L, 3L),
            ),
        )

        val clamped = VideoEpisodeQueue(episodes = episodes, currentIndex = 99)
        val restored = clamped.withCurrentPlaybackKey(episodes[1].playbackKey)

        assertEquals(2, clamped.currentIndex)
        assertTrue(clamped.hasPrevious)
        assertFalse(clamped.hasNext)
        assertEquals(1, restored.currentIndex)
        assertEquals(VideoEpisodeSource.WEB_DAV, restored.currentEpisode?.source)
        assertTrue(restored.hasPrevious)
        assertTrue(restored.hasNext)
    }

    @Test(expected = IllegalArgumentException::class)
    fun episodeQueueRejectsDuplicatePlaybackKeys() {
        val episode = VideoEpisode.local(
            LocalVideoOpenRequest("content://videos/1", "第 1 集.mkv", 1L, 1L),
        )

        VideoEpisodeQueue(episodes = listOf(episode, episode))
    }

    private fun customPlayerOptions(): VideoPlayerOptions =
        VideoPlayerOptions(
            resumeEnabled = false,
            videoOutputMode = VideoOutputMode.GPU_NEXT,
            gpuApiMode = GpuApiMode.VULKAN,
            videoDecoderMode = VideoDecoderMode.HARDWARE_PLUS,
            mpvProfileMode = MpvProfileMode.LOW_LATENCY,
            controlsAutoHideMillis = 8_000,
            playerOrientationMode = VideoPlayerOrientationMode.SENSOR,
            proxyDebugInfoEnabled = true,
            videoBackgroundMode = VideoBackgroundMode.BACKGROUND_PLAY,
            anime4kProfile = Anime4KProfile.EXTREME,
        )

}
