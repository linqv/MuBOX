package org.mubox.reader.video.player

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class VideoStartPositionTest {
    @Test
    fun resumeDisabledDoesNotReadStoredPosition() = runTest {
        var readCount = 0

        val position = loadVideoStartPosition(
            resumeEnabled = false,
            playbackKey = "movie",
            loadPosition = {
                readCount += 1
                42_000L
            },
        )

        assertEquals(0L, position)
        assertEquals(0, readCount)
    }

    @Test
    fun loadFailureFallsBackToStartInsteadOfSkippingPlayback() = runTest {
        val failures = mutableListOf<Throwable>()

        val position = loadVideoStartPosition(
            resumeEnabled = true,
            playbackKey = "movie",
            loadPosition = { error("datastore unavailable") },
            onFailure = { failures += it },
        )

        assertEquals(0L, position)
        assertEquals(1, failures.size)
    }
}
