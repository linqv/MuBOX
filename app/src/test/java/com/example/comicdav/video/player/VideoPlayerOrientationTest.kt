package com.example.comicdav.video.player

import android.content.pm.ActivityInfo
import android.content.res.Configuration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class VideoPlayerOrientationTest {
    @Test
    fun orientationModeLabelsExposeRequestedOptionsInOrder() {
        assertEquals(
            listOf("视频", "竖屏", "横屏", "传感器"),
            VideoPlayerOrientationMode.entries.map(::videoPlayerOrientationModeLabel),
        )
    }

    @Test
    fun videoModeDefaultsToLandscapeWhenDimensionsAreMissing() {
        assertEquals(
            ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE,
            requestedOrientationForVideoPlayerMode(VideoPlayerOrientationMode.VIDEO, VideoParams()),
        )
    }

    @Test
    fun videoModeUsesPortraitForTallVideo() {
        assertEquals(
            ActivityInfo.SCREEN_ORIENTATION_PORTRAIT,
            requestedOrientationForVideoPlayerMode(
                VideoPlayerOrientationMode.VIDEO,
                VideoParams(width = 720, height = 1280),
            ),
        )
    }

    @Test
    fun videoModeUsesLandscapeForWideOrSquareVideo() {
        assertEquals(
            ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE,
            requestedOrientationForVideoPlayerMode(
                VideoPlayerOrientationMode.VIDEO,
                VideoParams(width = 1920, height = 1080),
            ),
        )
        assertEquals(
            ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE,
            requestedOrientationForVideoPlayerMode(
                VideoPlayerOrientationMode.VIDEO,
                VideoParams(width = 1000, height = 1000),
            ),
        )
    }

    @Test
    fun fixedAndSensorModesMapToActivityOrientationConstants() {
        assertEquals(
            ActivityInfo.SCREEN_ORIENTATION_PORTRAIT,
            requestedOrientationForVideoPlayerMode(VideoPlayerOrientationMode.PORTRAIT, VideoParams()),
        )
        assertEquals(
            ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE,
            requestedOrientationForVideoPlayerMode(VideoPlayerOrientationMode.LANDSCAPE, VideoParams()),
        )
        assertEquals(
            ActivityInfo.SCREEN_ORIENTATION_SENSOR,
            requestedOrientationForVideoPlayerMode(VideoPlayerOrientationMode.SENSOR, VideoParams()),
        )
    }

    @Test
    fun manualToggleSwitchesBetweenFixedPortraitAndLandscape() {
        val session = VideoPlayerOrientationSession(VideoPlayerOrientationMode.VIDEO)

        assertEquals(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE, session.initialRequestedOrientation())
        assertEquals(
            ActivityInfo.SCREEN_ORIENTATION_PORTRAIT,
            session.toggleFixedOrientation(Configuration.ORIENTATION_LANDSCAPE),
        )
        assertEquals(
            ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE,
            session.toggleFixedOrientation(Configuration.ORIENTATION_PORTRAIT),
        )
    }

    @Test
    fun manualToggleDisablesLaterVideoAutoUpdatesForCurrentPlayback() {
        val session = VideoPlayerOrientationSession(VideoPlayerOrientationMode.VIDEO)

        session.initialRequestedOrientation()
        session.toggleFixedOrientation(Configuration.ORIENTATION_LANDSCAPE)

        assertNull(session.requestForVideoParams(VideoParams(width = 720, height = 1280)))
    }

    @Test
    fun videoModeSessionUpdatesOnlyWhenDimensionsAreKnownAndNotManuallyOverridden() {
        val session = VideoPlayerOrientationSession(VideoPlayerOrientationMode.VIDEO)

        assertEquals(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE, session.initialRequestedOrientation())
        assertNull(session.requestForVideoParams(VideoParams()))
        assertEquals(
            ActivityInfo.SCREEN_ORIENTATION_PORTRAIT,
            session.requestForVideoParams(VideoParams(width = 720, height = 1280)),
        )
    }

    @Test
    fun localIntentCarriesSelectedOrientationMode() {
        val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        val intent = VideoPlayerActivity.localIntent(
            context = context,
            request = com.example.comicdav.video.LocalVideoOpenRequest(
                uri = "content://video/movie.mp4",
                displayName = "movie.mp4",
                size = null,
                lastModified = null,
            ),
            playerOrientationMode = VideoPlayerOrientationMode.PORTRAIT,
        )

        assertEquals(
            VideoPlayerOrientationMode.PORTRAIT.name,
            intent.getStringExtra(VideoPlayerActivity.EXTRA_PLAYER_ORIENTATION_MODE),
        )
    }

    @Test
    fun webDavIntentCarriesSelectedOrientationMode() {
        val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        val intent = VideoPlayerActivity.webDavIntent(
            context = context,
            request = com.example.comicdav.video.WebDavVideoOpenRequest(
                accountId = "account",
                remotePath = "/movie.mp4",
                displayName = "movie.mp4",
                size = null,
                etag = null,
                lastModified = null,
                mimeType = null,
            ),
            uri = "http://127.0.0.1:8080/stream/movie",
            subtitleUrls = emptyList(),
            streamIds = emptyList(),
            playerOrientationMode = VideoPlayerOrientationMode.LANDSCAPE,
        )

        assertEquals(
            VideoPlayerOrientationMode.LANDSCAPE.name,
            intent.getStringExtra(VideoPlayerActivity.EXTRA_PLAYER_ORIENTATION_MODE),
        )
    }

    @Test
    fun nonVideoModeSessionDoesNotReactToVideoParams() {
        val session = VideoPlayerOrientationSession(VideoPlayerOrientationMode.SENSOR)

        assertEquals(ActivityInfo.SCREEN_ORIENTATION_SENSOR, session.initialRequestedOrientation())
        assertNull(session.requestForVideoParams(VideoParams(width = 720, height = 1280)))
    }
}
