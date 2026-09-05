package org.mubox.reader.video.player

import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ListenPlaybackModeTest {
    @Test
    fun modesExposeTheRequestedLabelsAndClearDescriptions() {
        assertEquals(
            listOf("顺序连播", "随机播放", "循环"),
            ListenPlaybackMode.entries.map(ListenPlaybackMode::controlLabel),
        )
        ListenPlaybackMode.entries.forEach { mode ->
            assertTrue(mode.detailText().isNotBlank())
        }
    }

    @Test
    fun sequentialPlaybackAdvancesAndStopsAtTheEnd() {
        assertEquals(2, nextListenEpisodeIndex(ListenPlaybackMode.SEQUENTIAL, 1, 3))
        assertNull(nextListenEpisodeIndex(ListenPlaybackMode.SEQUENTIAL, 2, 3))
        assertNull(nextListenEpisodeIndex(ListenPlaybackMode.SEQUENTIAL, 0, 0))
    }

    @Test
    fun loopPlaybackWrapsFromTheLastEpisodeToTheFirst() {
        assertEquals(2, nextListenEpisodeIndex(ListenPlaybackMode.LOOP, 1, 3))
        assertEquals(0, nextListenEpisodeIndex(ListenPlaybackMode.LOOP, 2, 3))
        assertEquals(0, nextListenEpisodeIndex(ListenPlaybackMode.LOOP, 0, 1))
    }

    @Test
    fun shufflePlaybackNeverRepeatsTheCurrentEpisodeWhenAlternativesExist() {
        repeat(20) { seed ->
            val next = nextListenEpisodeIndex(
                mode = ListenPlaybackMode.SHUFFLE,
                currentIndex = 2,
                episodeCount = 5,
                random = Random(seed),
            )
            assertNotEquals(2, next)
            assertTrue(next in 0..4)
        }
        assertEquals(0, nextListenEpisodeIndex(ListenPlaybackMode.SHUFFLE, 0, 1))
    }

    @Test
    fun savedModeRestoresSafelyAndFallsBackToSequential() {
        assertEquals(ListenPlaybackMode.LOOP, restoredListenPlaybackMode("LOOP"))
        assertEquals(ListenPlaybackMode.SEQUENTIAL, restoredListenPlaybackMode("unknown"))
        assertEquals(ListenPlaybackMode.SEQUENTIAL, restoredListenPlaybackMode(null))
    }
}
