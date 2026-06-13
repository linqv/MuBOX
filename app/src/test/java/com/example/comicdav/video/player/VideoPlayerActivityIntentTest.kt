package com.example.comicdav.video.player

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.comicdav.data.AppSettings
import com.example.comicdav.video.LocalVideoOpenRequest
import com.example.comicdav.video.WebDavVideoOpenRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class VideoPlayerActivityIntentTest {
    @Test
    fun appSettingsDefaultToAutomaticVideoBackendModes() {
        val settings = AppSettings()

        assertEquals(VideoOutputMode.AUTO, settings.videoOutputMode)
        assertEquals(GpuApiMode.AUTO, settings.gpuApiMode)
        assertEquals(VideoDecoderMode.AUTO, settings.videoDecoderMode)
        assertEquals(MpvProfileMode.FAST, settings.mpvProfileMode)
        assertEquals(5000, settings.videoControlsAutoHideMillis)
        assertFalse(settings.videoPlayerProxyDebugInfoEnabled)
    }

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
        assertFalse(intent.getBooleanExtra(VideoPlayerActivity.EXTRA_ANIME4K_ENABLED, true))
        assertEquals(
            Anime4KMode.A.name,
            intent.getStringExtra(VideoPlayerActivity.EXTRA_ANIME4K_MODE),
        )
        assertEquals(
            Anime4KQuality.FAST.name,
            intent.getStringExtra(VideoPlayerActivity.EXTRA_ANIME4K_QUALITY),
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

        val intent = VideoPlayerActivity.localIntent(
            context = context,
            request = request,
            videoOutputMode = VideoOutputMode.GPU_NEXT,
            gpuApiMode = GpuApiMode.VULKAN,
            videoDecoderMode = VideoDecoderMode.HARDWARE_PLUS,
            mpvProfileMode = MpvProfileMode.LOW_LATENCY,
            controlsAutoHideMillis = 8000,
            proxyDebugInfoEnabled = true,
            anime4kEnabled = true,
            anime4kMode = Anime4KMode.C_PLUS,
            anime4kQuality = Anime4KQuality.HIGH,
        )

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
            true,
            intent.getBooleanExtra(VideoPlayerActivity.EXTRA_ANIME4K_ENABLED, false),
        )
        assertEquals(
            Anime4KMode.C_PLUS.name,
            intent.getStringExtra(VideoPlayerActivity.EXTRA_ANIME4K_MODE),
        )
        assertEquals(
            Anime4KQuality.HIGH.name,
            intent.getStringExtra(VideoPlayerActivity.EXTRA_ANIME4K_QUALITY),
        )
    }

    @Test
    fun localIntentDoesNotCarryPlaybackQueueExtras() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val request = LocalVideoOpenRequest(
            uri = "content://media/external/video/42",
            displayName = "Episode 02.mkv",
            size = 2048L,
            lastModified = 200L,
        )

        val intent = VideoPlayerActivity.localIntent(context, request)

        assertFalse(intent.hasQueueExtras())
    }

    @Test
    fun webDavIntentDoesNotCarryPlaybackQueueExtras() {
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

        val intent = VideoPlayerActivity.webDavIntent(
            context = context,
            request = request,
            uri = "http://127.0.0.1:1234/stream/current",
            subtitleUrls = emptyList(),
            streamIds = listOf("current"),
            proxyDebugInfoEnabled = true,
            anime4kEnabled = true,
            anime4kMode = Anime4KMode.C_PLUS,
            anime4kQuality = Anime4KQuality.HIGH,
        )

        assertFalse(intent.hasQueueExtras())
        assertEquals(
            true,
            intent.getBooleanExtra(VideoPlayerActivity.EXTRA_PROXY_DEBUG_INFO_ENABLED, false),
        )
        assertEquals(
            true,
            intent.getBooleanExtra(VideoPlayerActivity.EXTRA_ANIME4K_ENABLED, false),
        )
        assertEquals(
            Anime4KMode.C_PLUS.name,
            intent.getStringExtra(VideoPlayerActivity.EXTRA_ANIME4K_MODE),
        )
        assertEquals(
            Anime4KQuality.HIGH.name,
            intent.getStringExtra(VideoPlayerActivity.EXTRA_ANIME4K_QUALITY),
        )
    }

    private fun android.content.Intent.hasQueueExtras(): Boolean =
        extras?.keySet().orEmpty().any { it.contains("QUEUE") }
}
