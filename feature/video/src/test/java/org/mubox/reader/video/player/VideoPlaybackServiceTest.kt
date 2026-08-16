package org.mubox.reader.video.player

import android.app.Notification
import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class VideoPlaybackServiceTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun expandedMediaNotificationShowsPlaybackInfoAndEpisodeControls() {
        val notification = VideoPlaybackService.buildNotification(
            context = context,
            state = VideoPlaybackNotificationState(
                displayName = "示例视频.mkv",
                isPaused = false,
                episodeNumber = 2,
                episodeCount = 8,
                hasPreviousEpisode = true,
                hasNextEpisode = true,
                statusText = "剩余 15 分钟",
            ),
            playbackSessionId = "session-a",
        )

        assertEquals("示例视频.mkv", notification.extras.getCharSequence(Notification.EXTRA_TITLE))
        assertEquals(
            "正在播放 · 第 2 / 8 集 · 剩余 15 分钟",
            notification.extras.getCharSequence(Notification.EXTRA_TEXT),
        )
        assertEquals(
            Notification.MediaStyle::class.java.name,
            notification.extras.getString(Notification.EXTRA_TEMPLATE),
        )
        assertEquals(
            listOf("上一集", "暂停", "下一集", "停止"),
            notification.actions.map { it.title.toString() },
        )
    }

    @Test
    fun pausedSingleVideoUsesPlayActionWithoutEpisodeNavigation() {
        val notification = VideoPlaybackService.buildNotification(
            context = context,
            state = VideoPlaybackNotificationState(
                displayName = "单个视频.mp4",
                isPaused = true,
            ),
            playbackSessionId = "session-a",
        )

        assertEquals("已暂停", notification.extras.getCharSequence(Notification.EXTRA_TEXT))
        assertEquals(listOf("播放", "停止"), notification.actions.map { it.title.toString() })
    }

    @Test
    fun playbackControlsAreScopedToTheActiveSession() {
        val matchingIntent = VideoPlaybackService.playbackControlIntent(
            playbackSessionId = "session-a",
            control = VideoPlaybackNotificationControl.NEXT_EPISODE,
        )

        assertEquals(
            VideoPlaybackNotificationControl.NEXT_EPISODE,
            VideoPlaybackService.playbackControlForSession(matchingIntent, "session-a"),
        )
        assertNull(VideoPlaybackService.playbackControlForSession(matchingIntent, "session-b"))
        assertNull(
            VideoPlaybackService.playbackControlForSession(
                Intent(VideoPlaybackService.ACTION_PLAYBACK_CONTROL),
                "session-a",
            ),
        )
    }
}
