package org.mubox.reader.feature.home

import org.mubox.reader.core.model.history.WatchHistoryEntry
import org.mubox.reader.core.model.history.WatchMediaType
import org.mubox.reader.core.model.history.WatchSourceType
import org.mubox.reader.core.model.library.LibraryItem
import org.mubox.reader.core.model.library.LibraryItemWithSources
import org.mubox.reader.core.model.library.LocalComicSource
import org.mubox.reader.core.model.library.SourceType
import org.mubox.reader.core.model.videolibrary.VideoLibraryItem
import org.mubox.reader.core.model.videolibrary.VideoLibraryItemWithSources
import org.mubox.reader.core.model.videolibrary.VideoSourceType
import org.mubox.reader.core.model.videolibrary.WebDavVideoSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HomeCoverLookupTest {
    @Test
    fun localComicHistoryUsesMatchingLibraryCover() {
        val entry = historyEntry(
            mediaType = WatchMediaType.COMIC,
            sourceType = WatchSourceType.LOCAL,
            sourceLocator = "content://comic/1",
        )
        val comic = LibraryItemWithSources(
            item = LibraryItem(
                id = 1L,
                title = "comic.cbz",
                displayName = "漫画",
                sourceType = SourceType.LOCAL,
                coverPath = "/covers/comic.webp",
                addedAt = 1L,
            ),
            localSource = LocalComicSource(
                libraryItemId = 1L,
                uri = "content://comic/1",
                fileName = "comic.cbz",
            ),
            webDavSource = null,
        )

        assertEquals(
            "/covers/comic.webp",
            homeHistoryCoverPath(entry, comics = listOf(comic), videos = emptyList()),
        )
    }

    @Test
    fun webDavVideoHistoryUsesMatchingThumbnail() {
        val entry = historyEntry(
            mediaType = WatchMediaType.VIDEO,
            sourceType = WatchSourceType.WEB_DAV,
            sourceLocator = "/videos/movie.mp4",
        )
        val video = VideoLibraryItemWithSources(
            item = VideoLibraryItem(
                id = 2L,
                title = "movie.mp4",
                displayName = "影片",
                sourceType = VideoSourceType.WEBDAV,
                thumbnailPath = "/covers/movie.webp",
                addedAt = 1L,
            ),
            localSource = null,
            webDavSource = WebDavVideoSource(
                videoLibraryItemId = 2L,
                accountId = "account",
                remotePath = "/videos/movie.mp4",
                fileName = "movie.mp4",
            ),
        )

        assertEquals(
            "/covers/movie.webp",
            homeHistoryCoverPath(entry, comics = emptyList(), videos = listOf(video)),
        )
    }

    @Test
    fun unmatchedHistoryDoesNotBorrowAnotherCover() {
        val entry = historyEntry(
            mediaType = WatchMediaType.COMIC,
            sourceType = WatchSourceType.LOCAL,
            sourceLocator = "content://comic/missing",
        )

        assertNull(homeHistoryCoverPath(entry, comics = emptyList(), videos = emptyList()))
    }

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
