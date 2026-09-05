package org.mubox.reader.video.player

import org.junit.Assert.assertEquals
import org.junit.Test

class AudioListenScreenUiTest {
    @Test
    fun listenScreenExposesBackAndTransportControls() {
        assertEquals(
            listOf("退出听视频，返回视频画面", "播放", "暂停", "上一集", "下一集"),
            listenScreenTransportControlDescriptions(),
        )
    }

    @Test
    fun listenScreenExposesDedicatedProgressControl() {
        assertEquals("播放进度", LISTEN_PROGRESS_CONTENT_DESCRIPTION)
        assertEquals("当前视频封面", LISTEN_COVER_CONTENT_DESCRIPTION)
    }

    @Test
    fun listenScreenExposesPersistentTimerControl() {
        assertEquals("定时关闭", LISTEN_TIMER_CONTENT_DESCRIPTION)
        assertEquals(
            "播放设置：播放方式、倍速和定时关闭",
            LISTEN_PLAYBACK_SETTINGS_CONTENT_DESCRIPTION,
        )
    }

    @Test
    fun listenScreenExposesQuickControlGroups() {
        assertEquals(
            listOf("播放设置", "播放方式", "倍速", "定时关闭", "选集"),
            listenScreenQuickControlLabels(),
        )
    }

    @Test
    fun discCenterShowsEpisodeNumberForMultiEpisodeQueue() {
        assertEquals("3", listenDiscCenterLabel(episodeIndex = 2, queueSize = 12))
        assertEquals("1", listenDiscCenterLabel(episodeIndex = 0, queueSize = 2))
    }

    @Test
    fun discCenterFallsBackToMusicNoteWithoutMultiEpisodeQueue() {
        assertEquals("♪", listenDiscCenterLabel(episodeIndex = 0, queueSize = 1))
        assertEquals("♪", listenDiscCenterLabel(episodeIndex = 0, queueSize = null))
    }

    @Test
    fun subtitleShowsEpisodePositionWhenQueueIsPresent() {
        assertEquals(
            "第 3 / 12 集 · 听视频",
            listenSubtitleText(queueSize = 12, currentEpisodeIndex = 2, source = "local"),
        )
    }

    @Test
    fun subtitleClampsEpisodePositionIntoQueueBounds() {
        assertEquals(
            "第 12 / 12 集 · 听视频",
            listenSubtitleText(queueSize = 12, currentEpisodeIndex = 20, source = "local"),
        )
    }

    @Test
    fun subtitleShowsFriendlySourceWithoutQueue() {
        assertEquals(
            "听视频 · 本地视频",
            listenSubtitleText(queueSize = null, currentEpisodeIndex = 0, source = "local"),
        )
        assertEquals(
            "听视频 · WebDAV",
            listenSubtitleText(queueSize = null, currentEpisodeIndex = 0, source = "webdav"),
        )
    }

    @Test
    fun unknownSourceKeepsRawLabel() {
        assertEquals(
            "听视频 · smb",
            listenSubtitleText(queueSize = null, currentEpisodeIndex = 0, source = "smb"),
        )
    }

    @Test
    fun selectedAudioTrackFallsBackToAutoLabel() {
        assertEquals(
            "自动",
            listenSelectedAudioTrackLabel(MpvPlayerState(audioTracks = emptyList())),
        )
        assertEquals(
            "日语",
            listenSelectedAudioTrackLabel(
                MpvPlayerState(
                    audioTracks = listOf(
                        MpvTrack(id = 1, type = MpvTrackType.AUDIO, title = "日语"),
                        MpvTrack(id = 2, type = MpvTrackType.AUDIO, title = "国语"),
                    ),
                    selectedAudioTrackId = 1,
                ),
            ),
        )
    }
}
