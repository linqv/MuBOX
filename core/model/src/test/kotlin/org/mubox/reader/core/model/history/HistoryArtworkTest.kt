package org.mubox.reader.core.model.history

import org.mubox.reader.core.model.media.videoThumbnailFile
import org.mubox.reader.core.model.media.webDavVideoThumbnailStableKey
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

    @Test
    fun videoHistoryUsesTheSameContentAddressedFileAsOtherVideoSurfaces() {
        val entry = videoEntry(etag = "v1")
        val sharedStableKey = webDavVideoThumbnailStableKey(
            accountId = "account",
            remotePath = "/history.mp4",
            size = null,
            etag = "v1",
            lastModified = null,
        )

        assertEquals(sharedStableKey, historyThumbnailStableKey(entry))
        assertEquals(
            videoThumbnailFile(temporaryFolder.root, sharedStableKey),
            historyThumbnailFile(temporaryFolder.root, entry),
        )
    }

    @Test
    fun comicHistoryUsesV2StableKeyAndInvalidatesLegacyKey() {
        val entry = comicEntry()
        val stableKey = historyThumbnailStableKey(entry)
        assertTrue(stableKey.startsWith("history-v2\u001FCOMIC\u001F"))

        val legacyKey = listOf(
            "history",
            entry.mediaType.name,
            entry.mediaKey,
            entry.sourceLocator,
            entry.accountId.orEmpty(),
            entry.size?.toString().orEmpty(),
            entry.etag.orEmpty(),
            entry.lastModified?.toString().orEmpty(),
        ).joinToString(separator = "\u001F")

        assertTrue(stableKey != legacyKey)
    }

    @Test
    fun resolvedHistoryArtworkPathRejectsNonExistentLibraryCoverAndFallsBack() {
        val entry = comicEntry()
        val missingLibraryItem = org.mubox.reader.core.model.library.LibraryItemWithSources(
            item = org.mubox.reader.core.model.library.LibraryItem(
                id = 1L,
                title = "Test Comic",
                displayName = "Test Comic",
                sourceType = org.mubox.reader.core.model.library.SourceType.WEBDAV,
                coverPath = temporaryFolder.root.resolve("library-covers/nonexistent.img").absolutePath,
                addedAt = 0L,
            ),
            localSource = null,
            webDavSource = org.mubox.reader.core.model.library.WebDavComicSource(
                libraryItemId = 1L,
                accountId = "account",
                remotePath = "/comic.cbz",
                fileName = "comic.cbz",
            ),
        )

        // Library cover file does not exist on disk, no history thumbnail exists -> null
        assertEquals(
            null,
            resolvedHistoryArtworkPath(
                entry = entry,
                comics = listOf(missingLibraryItem),
                videos = emptyList(),
                cacheDir = temporaryFolder.root,
            ),
        )
        assertEquals(
            listOf(entry),
            historyEntriesNeedingThumbnails(
                history = listOf(entry),
                comics = listOf(missingLibraryItem),
                videos = emptyList(),
                cacheDir = temporaryFolder.root,
            ),
        )

        // Now create the history thumbnail file -> falls back to history thumbnail
        val thumbFile = historyThumbnailFile(temporaryFolder.root, entry)
        thumbFile.parentFile!!.mkdirs()
        thumbFile.writeText("history-thumbnail")

        assertEquals(
            thumbFile.absolutePath,
            resolvedHistoryArtworkPath(
                entry = entry,
                comics = listOf(missingLibraryItem),
                videos = emptyList(),
                cacheDir = temporaryFolder.root,
            ),
        )

        // Now create the library cover file on disk -> uses library cover
        val realLibraryCover = temporaryFolder.root.resolve("library-covers/real.img")
        realLibraryCover.parentFile!!.mkdirs()
        realLibraryCover.writeText("library-cover")
        val existingLibraryItem = missingLibraryItem.copy(
            item = missingLibraryItem.item.copy(coverPath = realLibraryCover.absolutePath),
        )

        assertEquals(
            realLibraryCover.absolutePath,
            resolvedHistoryArtworkPath(
                entry = entry,
                comics = listOf(existingLibraryItem),
                videos = emptyList(),
                cacheDir = temporaryFolder.root,
            ),
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

    private fun comicEntry(): WatchHistoryEntry =
        WatchHistoryEntry(
            mediaKey = "comic:history",
            mediaType = WatchMediaType.COMIC,
            title = "comic.cbz",
            sourceType = WatchSourceType.WEB_DAV,
            sourceLocator = "/comic.cbz",
            accountId = "account",
            etag = "v1",
            progress = 1L,
            total = 10L,
            lastWatchedAt = 100L,
        )
}
