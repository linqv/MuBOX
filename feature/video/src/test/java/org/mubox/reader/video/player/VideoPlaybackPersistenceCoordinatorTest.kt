package org.mubox.reader.video.player

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class VideoPlaybackPersistenceCoordinatorTest {
    @Test
    fun resumeDisabledSkipsLoadingSavingAndAutoSave() = runTest {
        var loadCount = 0
        var progressReadCount = 0
        val savedPositions = mutableListOf<SavedPosition>()
        val coordinator = VideoPlaybackPersistenceCoordinator(
            autoSaveScope = this,
            resumeEnabled = false,
            initialPlaybackKey = "episode-1",
            loadPosition = {
                loadCount += 1
                42_000L
            },
            savePositionAsync = { key, position, duration ->
                savedPositions += SavedPosition(key, position, duration)
            },
            currentProgress = {
                progressReadCount += 1
                VideoPlaybackProgressState(durationMillis = 60_000L, positionMillis = 10_000L)
            },
            autoSaveIntervalMillis = 1_000L,
        )

        assertEquals(0L, coordinator.loadStartPosition())
        coordinator.saveCurrentPositionAsync()
        coordinator.startAutoSave()
        advanceTimeBy(2_000L)
        runCurrent()

        assertEquals(0, loadCount)
        assertEquals(0, progressReadCount)
        assertEquals(emptyList<SavedPosition>(), savedPositions)
    }

    @Test
    fun adoptedPlaybackKeyDrivesManualAndPeriodicSaves() = runTest {
        var progress = VideoPlaybackProgressState(durationMillis = 60_000L, positionMillis = 10_000L)
        val loadedKeys = mutableListOf<String?>()
        val savedPositions = mutableListOf<SavedPosition>()
        val coordinator = VideoPlaybackPersistenceCoordinator(
            autoSaveScope = this,
            resumeEnabled = true,
            initialPlaybackKey = "episode-1",
            loadPosition = { key ->
                loadedKeys += key
                if (key == "episode-2") 20_000L else 10_000L
            },
            savePositionAsync = { key, position, duration ->
                savedPositions += SavedPosition(key, position, duration)
            },
            currentProgress = { progress },
            autoSaveIntervalMillis = 1_000L,
        )

        assertEquals(10_000L, coordinator.loadStartPosition())
        assertEquals(20_000L, coordinator.loadStartPosition("episode-2"))
        coordinator.saveCurrentPositionAsync()
        coordinator.adoptPlaybackKey("episode-2")
        progress = VideoPlaybackProgressState(durationMillis = 90_000L, positionMillis = 25_000L)
        coordinator.startAutoSave()
        coordinator.startAutoSave()
        advanceTimeBy(1_000L)
        runCurrent()
        coordinator.stopAutoSave()
        advanceTimeBy(2_000L)
        runCurrent()

        assertEquals(listOf("episode-1", "episode-2"), loadedKeys)
        assertEquals(
            listOf(
                SavedPosition("episode-1", positionMillis = 10_000L, durationMillis = 60_000L),
                SavedPosition("episode-2", positionMillis = 25_000L, durationMillis = 90_000L),
            ),
            savedPositions,
        )
    }

    @Test
    fun transitionCheckpointStopsOldKeyAutoSaveUntilNewKeyIsAdopted() = runTest {
        var progress = VideoPlaybackProgressState(durationMillis = 60_000L, positionMillis = 10_000L)
        val savedPositions = mutableListOf<SavedPosition>()
        val coordinator = VideoPlaybackPersistenceCoordinator(
            autoSaveScope = this,
            resumeEnabled = true,
            initialPlaybackKey = "episode-1",
            loadPosition = { 0L },
            savePositionAsync = { key, position, duration ->
                savedPositions += SavedPosition(key, position, duration)
            },
            currentProgress = { progress },
            autoSaveIntervalMillis = 1_000L,
        )

        coordinator.startAutoSave()
        coordinator.checkpointAndPauseForTransition()
        progress = VideoPlaybackProgressState(durationMillis = 90_000L, positionMillis = 0L)
        advanceTimeBy(2_000L)
        runCurrent()

        coordinator.adoptPlaybackKey("episode-2")
        progress = VideoPlaybackProgressState(durationMillis = 90_000L, positionMillis = 25_000L)
        coordinator.startAutoSave()
        advanceTimeBy(1_000L)
        runCurrent()
        coordinator.stopAutoSave()

        assertEquals(
            listOf(
                SavedPosition("episode-1", positionMillis = 10_000L, durationMillis = 60_000L),
                SavedPosition("episode-2", positionMillis = 25_000L, durationMillis = 90_000L),
            ),
            savedPositions,
        )
    }

    private data class SavedPosition(
        val playbackKey: String,
        val positionMillis: Long,
        val durationMillis: Long,
    )
}
