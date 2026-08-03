package org.mubox.reader.feature.home

import org.mubox.reader.core.model.history.WatchHistoryEntry
import org.mubox.reader.core.model.history.WatchMediaType
import org.mubox.reader.core.model.history.WatchSourceType
import org.junit.Assert.assertEquals
import org.junit.Test

class HomeCoverLookupTest {
    @Test
    fun recentProgressUsesPercentageInsteadOfMediaUnits() {
        val knownTotal = historyEntry(
            mediaType = WatchMediaType.COMIC,
            sourceType = WatchSourceType.LOCAL,
            sourceLocator = "content://comic/progress",
        ).copy(progress = 25L, total = 100L)
        val unknownTotal = knownTotal.copy(total = 0L)

        assertEquals("25%", homeHistoryProgressPercentLabel(knownTotal))
        assertEquals("--%", homeHistoryProgressPercentLabel(unknownTotal))
    }

    @Test
    fun sharedVideoRevisionOnlyInvalidatesVideoHistoryArtwork() {
        val video = historyEntry(
            mediaType = WatchMediaType.VIDEO,
            sourceType = WatchSourceType.LOCAL,
            sourceLocator = "content://video/revision",
        )
        val comic = historyEntry(
            mediaType = WatchMediaType.COMIC,
            sourceType = WatchSourceType.LOCAL,
            sourceLocator = "content://comic/revision",
        )

        assertEquals(7L, homeHistoryArtworkRevision(video, entryRevision = 2L, sharedVideoRevision = 5L))
        assertEquals(2L, homeHistoryArtworkRevision(comic, entryRevision = 2L, sharedVideoRevision = 5L))
    }

    private fun historyEntry(
        mediaType: WatchMediaType,
        sourceType: WatchSourceType,
        sourceLocator: String,
    ) = WatchHistoryEntry(
        mediaKey = "$mediaType:$sourceLocator",
        mediaType = mediaType,
        title = "媒体",
        sourceType = sourceType,
        sourceLocator = sourceLocator,
        accountId = "account".takeIf { sourceType == WatchSourceType.WEB_DAV },
        progress = 1L,
        total = 10L,
        lastWatchedAt = 1L,
    )
}
