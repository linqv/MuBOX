package com.example.comicdav.video.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoPlaybackQueueTest {
    @Test
    fun queueExposesPreviousAndNextItemsAroundCurrentIndex() {
        val queue = VideoPlaybackQueue(
            items = listOf(
                VideoQueueItem(playbackKey = "k1", displayName = "01.mkv", sourceUri = "content://1", source = VideoQueueSource.LOCAL),
                VideoQueueItem(playbackKey = "k2", displayName = "02.mkv", sourceUri = "content://2", source = VideoQueueSource.LOCAL),
                VideoQueueItem(playbackKey = "k3", displayName = "03.mkv", sourceUri = "content://3", source = VideoQueueSource.LOCAL),
            ),
            currentIndex = 1,
        )

        assertEquals("01.mkv", queue.previousItem()?.displayName)
        assertEquals("03.mkv", queue.nextItem()?.displayName)
        assertTrue(queue.hasPrevious)
        assertTrue(queue.hasNext)
    }

    @Test
    fun queueClampsOutOfRangeCurrentIndex() {
        val queue = VideoPlaybackQueue(
            items = listOf(
                VideoQueueItem(playbackKey = "k1", displayName = "01.mkv", sourceUri = "content://1", source = VideoQueueSource.LOCAL),
            ),
            currentIndex = 99,
        )

        assertEquals(0, queue.currentIndex)
        assertFalse(queue.hasPrevious)
        assertFalse(queue.hasNext)
    }

    @Test
    fun moveNextAndPreviousKeepStablePlaybackKeys() {
        val queue = VideoPlaybackQueue(
            items = listOf(
                VideoQueueItem(playbackKey = "stable-1", displayName = "01.mkv", sourceUri = "content://1", source = VideoQueueSource.LOCAL),
                VideoQueueItem(playbackKey = "stable-2", displayName = "02.mkv", sourceUri = "content://2", source = VideoQueueSource.LOCAL),
            ),
            currentIndex = 0,
        )

        val next = queue.moveNext()
        val previous = next.movePrevious()

        assertEquals("stable-2", next.currentItem?.playbackKey)
        assertEquals("stable-1", previous.currentItem?.playbackKey)
    }

    @Test
    fun switcherClosesOldWebDavStreamsBeforeOpeningNextItem() {
        val events = mutableListOf<String>()
        val switcher = VideoQueueSwitcher(
            closeWebDavStreams = { streamIds -> events += "close:${streamIds.joinToString(",")}" },
            openQueueItem = { item ->
                events += "open:${item.displayName}"
                VideoQueueOpenResult(
                    playbackUri = item.sourceUri,
                    webDavStreamIds = listOf("new-main", "new-sub"),
                )
            },
        )
        val currentSession = VideoQueueSession(
            queue = VideoPlaybackQueue(
                items = listOf(
                    VideoQueueItem("k1", "01.mkv", "http://127.0.0.1/stream/old", VideoQueueSource.WEB_DAV),
                    VideoQueueItem("k2", "02.mkv", "http://127.0.0.1/stream/new", VideoQueueSource.WEB_DAV),
                ),
                currentIndex = 0,
            ),
            playbackUri = "http://127.0.0.1/stream/old",
            webDavStreamIds = listOf("old-main", "old-sub"),
        )

        val nextSession = switcher.switchTo(currentSession, currentSession.queue.moveNext())

        assertEquals(listOf("close:old-main,old-sub", "open:02.mkv"), events)
        assertEquals(listOf("new-main", "new-sub"), nextSession.webDavStreamIds)
        assertEquals("k2", nextSession.queue.currentItem?.playbackKey)
    }
}
