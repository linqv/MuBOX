package org.mubox.reader.video.player

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoPlaybackProgressSaverTest {
    @Test
    fun saveAsyncReturnsBeforeDataStoreWriteCompletes() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        var saveStarted = false
        var saveCompleted = false
        val saver = VideoPlaybackProgressSaver(
            scope = CoroutineScope(SupervisorJob() + dispatcher),
            savePosition = { _, _, _ ->
                saveStarted = true
                delay(1_000)
                saveCompleted = true
            },
        )

        saver.saveAsync(
            playbackKey = "video-key",
            positionMillis = 15_000L,
            durationMillis = 60_000L,
        )

        assertFalse(saveStarted)
        assertFalse(saveCompleted)

        testScheduler.runCurrent()

        assertTrue(saveStarted)
        assertFalse(saveCompleted)

        testScheduler.advanceUntilIdle()

        assertTrue(saveCompleted)
    }

    @Test
    fun saveAsyncContainsWriteFailuresInsideSaveJob() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val unhandledFailures = mutableListOf<Throwable>()
        val exceptionHandler = CoroutineExceptionHandler { _, error ->
            unhandledFailures += error
        }
        val saver = VideoPlaybackProgressSaver(
            scope = CoroutineScope(SupervisorJob() + dispatcher + exceptionHandler),
            savePosition = { _, _, _ ->
                throw IllegalStateException("write failed")
            },
        )

        val job = saver.saveAsync(
            playbackKey = "video-key",
            positionMillis = 15_000L,
            durationMillis = 60_000L,
        )

        testScheduler.runCurrent()

        assertTrue(job!!.isCompleted)
        assertFalse(job.isCancelled)
        assertEquals(emptyList<Throwable>(), unhandledFailures)
    }

    @Test
    fun savingNextEpisodeDoesNotCancelPreviousEpisodeWrite() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val savedKeys = mutableListOf<String>()
        val saver = VideoPlaybackProgressSaver(
            scope = CoroutineScope(SupervisorJob() + dispatcher),
            savePosition = { key, _, _ ->
                if (key == "episode-1") delay(1_000)
                savedKeys += key
            },
        )

        val first = saver.saveAsync("episode-1", 15_000L, 60_000L)
        testScheduler.runCurrent()
        val second = saver.saveAsync("episode-2", 20_000L, 60_000L)

        testScheduler.advanceUntilIdle()

        assertTrue(first!!.isCompleted)
        assertFalse(first.isCancelled)
        assertTrue(second!!.isCompleted)
        assertEquals(listOf("episode-2", "episode-1"), savedKeys)
    }
}
