package com.example.comicdav.core.model.history

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class HistoryArtworkTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun stableCacheFileSatisfiesMissingArtworkLookup() {
        val entry = videoEntry()
        val cacheFile = historyThumbnailFile(temporaryFolder.root, entry)
        cacheFile.parentFile!!.mkdirs()
        cacheFile.writeText("thumbnail")

        assertTrue(cacheFile.name.endsWith(".jpg"))
        assertEquals(
            cacheFile.absolutePath,
            resolvedHistoryArtworkPath(
                entry = entry,
                comics = emptyList(),
                videos = emptyList(),
                cacheDir = temporaryFolder.root,
            ),
        )
        assertEquals(
            emptyList<WatchHistoryEntry>(),
            historyEntriesNeedingThumbnails(
                history = listOf(entry),
                comics = emptyList(),
                videos = emptyList(),
                cacheDir = temporaryFolder.root,
            ),
        )
    }

    @Test
    fun cacheIdentityChangesWithRemoteValidator() {
        val first = videoEntry(etag = "v1")
        val second = first.copy(etag = "v2")

        assertTrue(historyThumbnailStableKey(first) != historyThumbnailStableKey(second))
        assertTrue(
            historyThumbnailFile(temporaryFolder.root, first) !=
                historyThumbnailFile(temporaryFolder.root, second),
        )
    }

    private fun videoEntry(etag: String? = null): WatchHistoryEntry =
        WatchHistoryEntry(
            mediaKey = "video:history",
            mediaType = WatchMediaType.VIDEO,
            title = "history.mp4",
            sourceType = WatchSourceType.WEB_DAV,
            sourceLocator = "/history.mp4",
            accountId = "account",
            etag = etag,
            progress = 1L,
            total = 10L,
            lastWatchedAt = 100L,
        )
}
