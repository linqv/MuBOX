package com.example.comicdav.video.proxy

import kotlinx.coroutines.Job
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoPrefetchCoordinatorTest {
    @Test
    fun newNonOverlappingGenerationCancelsStaleJobAndSegments() {
        val stats = registeredStats("stream-1")
        val cancelled = mutableListOf<List<VideoSegmentKey>>()
        val coordinator = VideoPrefetchCoordinator(stats, cancelled::add)
        val firstGeneration = coordinator.beginForegroundGeneration("stream-1", setOf(0L))
        val staleJob = Job()
        val staleKeys = listOf(
            VideoSegmentKey("stream-1", 1L),
            VideoSegmentKey("stream-1", 2L),
        )
        assertTrue(
            coordinator.registerJob(
                streamId = "stream-1",
                generation = firstGeneration,
                keys = staleKeys,
                job = staleJob,
                scheduledState = "scheduled 1,2",
            ),
        )

        val nextGeneration = coordinator.beginForegroundGeneration("stream-1", setOf(4L))

        assertEquals(2L, nextGeneration)
        assertTrue(staleJob.isCancelled)
        assertEquals(listOf(staleKeys), cancelled)
        assertFalse(coordinator.isCurrentGeneration("stream-1", firstGeneration))
        assertTrue(coordinator.isCurrentGeneration("stream-1", nextGeneration))
        assertNull(stats.snapshot("stream-1")?.prefetchState)
    }

    @Test
    fun overlappingForegroundPreservesSharedFetchWhileInvalidatingOldGeneration() {
        val stats = registeredStats("stream-1")
        val cancelled = mutableListOf<List<VideoSegmentKey>>()
        val coordinator = VideoPrefetchCoordinator(stats, cancelled::add)
        val firstGeneration = coordinator.beginForegroundGeneration("stream-1", setOf(0L))
        val sharedJob = Job()
        coordinator.registerJob(
            streamId = "stream-1",
            generation = firstGeneration,
            keys = listOf(VideoSegmentKey("stream-1", 1L)),
            job = sharedJob,
            scheduledState = "scheduled 1",
        )

        try {
            val nextGeneration = coordinator.beginForegroundGeneration("stream-1", setOf(1L))

            assertFalse(sharedJob.isCancelled)
            assertTrue(cancelled.isEmpty())
            assertFalse(coordinator.isCurrentGeneration("stream-1", firstGeneration))
            assertTrue(coordinator.isCurrentGeneration("stream-1", nextGeneration))
            assertFalse(
                coordinator.publishStateIfCurrent(
                    streamId = "stream-1",
                    generation = firstGeneration,
                    prefetchState = "stale",
                    job = sharedJob,
                ),
            )
        } finally {
            sharedJob.cancel()
            coordinator.close()
        }
    }

    @Test
    fun onlyCurrentRegisteredJobCanPublishAndFinish() {
        val stats = registeredStats("stream-1")
        val coordinator = VideoPrefetchCoordinator(stats) {}
        val generation = coordinator.beginForegroundGeneration("stream-1", setOf(0L))
        val currentJob = Job()
        val otherJob = Job()
        coordinator.registerJob(
            streamId = "stream-1",
            generation = generation,
            keys = listOf(VideoSegmentKey("stream-1", 1L)),
            job = currentJob,
            scheduledState = "scheduled 1",
        )

        try {
            assertFalse(
                coordinator.publishStateIfCurrent(
                    streamId = "stream-1",
                    generation = generation,
                    prefetchState = "wrong job",
                    job = otherJob,
                ),
            )
            assertEquals("scheduled 1", stats.snapshot("stream-1")?.prefetchState)
            assertFalse(coordinator.finishJob("stream-1", generation, otherJob, "wrong finish"))
            assertEquals("scheduled 1", stats.snapshot("stream-1")?.prefetchState)

            assertTrue(coordinator.publishStateIfCurrent("stream-1", generation, "skipped cache_hit", currentJob))
            assertEquals("skipped cache_hit", stats.snapshot("stream-1")?.prefetchState)
            assertTrue(coordinator.finishJob("stream-1", generation, currentJob, "completed 1"))
            assertEquals("completed 1", stats.snapshot("stream-1")?.prefetchState)
        } finally {
            currentJob.cancel()
            otherJob.cancel()
            coordinator.close()
        }
    }

    @Test
    fun streamCancellationAndCloseCancelOnlyTheirOwnedState() {
        val stats = VideoProxyStatsStore().apply {
            registerStream("stream-1")
            registerStream("stream-2")
        }
        val cancelled = mutableListOf<List<VideoSegmentKey>>()
        val coordinator = VideoPrefetchCoordinator(stats, cancelled::add)
        val firstGeneration = coordinator.beginForegroundGeneration("stream-1", setOf(0L))
        val secondGeneration = coordinator.beginForegroundGeneration("stream-2", setOf(0L))
        val firstJob = Job()
        val secondJob = Job()
        val firstKey = VideoSegmentKey("stream-1", 1L)
        val secondKey = VideoSegmentKey("stream-2", 1L)
        coordinator.registerJob("stream-1", firstGeneration, listOf(firstKey), firstJob, "scheduled 1")
        coordinator.registerJob("stream-2", secondGeneration, listOf(secondKey), secondJob, "scheduled 1")

        coordinator.cancelStream("stream-1")

        assertTrue(firstJob.isCancelled)
        assertFalse(secondJob.isCancelled)
        assertEquals(listOf(listOf(firstKey)), cancelled)
        assertNull(stats.snapshot("stream-1")?.prefetchState)
        assertEquals("scheduled 1", stats.snapshot("stream-2")?.prefetchState)

        coordinator.close()

        assertTrue(secondJob.isCancelled)
        assertEquals(listOf(listOf(firstKey), listOf(secondKey)), cancelled)
    }

    private fun registeredStats(streamId: String): VideoProxyStatsStore =
        VideoProxyStatsStore().apply { registerStream(streamId) }
}
