package com.example.comicdav.ui.directorylisting

import com.example.comicdav.core.model.media.MediaKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DirectoryListingControlsTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun viewModeButtonDescribesTheModeItWillOpen() {
        assertEquals(
            "切换为网格视图",
            directoryViewModeActionLabel(DirectoryListingViewMode.LIST),
        )
        assertEquals(
            "切换为列表视图",
            directoryViewModeActionLabel(DirectoryListingViewMode.GRID),
        )
    }

    @Test
    fun sortFieldsHaveClearChineseLabels() {
        assertEquals("名称", directorySortFieldLabel(DirectorySortField.NAME))
        assertEquals("大小", directorySortFieldLabel(DirectorySortField.SIZE))
        assertEquals("文件类型", directorySortFieldLabel(DirectorySortField.TYPE))
    }

    @Test
    fun directionButtonDescribesTheActionItWillPerform() {
        assertEquals(
            "切换为降序",
            directorySortDirectionActionLabel(DirectorySortDirection.ASCENDING),
        )
        assertEquals(
            "切换为升序",
            directorySortDirectionActionLabel(DirectorySortDirection.DESCENDING),
        )
    }

    @Test
    fun compactSortButtonAnnouncesSelectedFieldAndDirection() {
        assertEquals(
            "排序：文件类型，降序",
            directorySortButtonDescription(
                sortField = DirectorySortField.TYPE,
                sortDirection = DirectorySortDirection.DESCENDING,
            ),
        )
    }

    @Test
    fun compactBreadcrumbKeepsTheCurrentAndParentDirectories() {
        assertEquals(
            listOf("…", "系列", "第一卷"),
            compactDirectoryBreadcrumbLabels(listOf("webdav", "漫画", "系列", "第一卷")),
        )
    }

    @Test
    fun gridThumbnailRequestsRespectTheGlobalSwitchAndArtworkState() {
        assertFalse(
            shouldRequestDirectoryVideoThumbnail(
                enabled = false,
                mediaKind = MediaKind.Video,
                hasArtwork = false,
            ),
        )
        assertFalse(
            shouldRequestDirectoryVideoThumbnail(
                enabled = true,
                mediaKind = MediaKind.Directory,
                hasArtwork = false,
            ),
        )
        assertFalse(
            shouldRequestDirectoryVideoThumbnail(
                enabled = true,
                mediaKind = MediaKind.Video,
                hasArtwork = true,
            ),
        )
        assertTrue(
            shouldRequestDirectoryVideoThumbnail(
                enabled = true,
                mediaKind = MediaKind.Video,
                hasArtwork = false,
            ),
        )
    }

    @Test
    fun artworkMemoryCacheKeyChangesWhenTheCachedFileIsReplaced() {
        val thumbnail = temporaryFolder.newFile("movie.jpg").apply {
            writeText("old")
            setLastModified(1_000L)
        }
        val firstKey = directoryVideoArtworkMemoryCacheKey(
            file = thumbnail,
            artworkRevision = 1L,
        )

        thumbnail.writeText("new thumbnail")
        thumbnail.setLastModified(2_000L)
        val secondKey = directoryVideoArtworkMemoryCacheKey(
            file = thumbnail,
            artworkRevision = 1L,
        )

        assertNotEquals(firstKey, secondKey)
    }

    @Test
    fun thumbnailStateEvictsTheLeastRecentlyUpdatedEntryAtItsLimit() {
        val first = DirectoryVideoThumbnail("v1", "/cache/first.jpg")
        val second = DirectoryVideoThumbnail("v2", "/cache/second.jpg")
        val third = DirectoryVideoThumbnail("v3", "/cache/third.jpg")
        var thumbnails = putBoundedDirectoryVideoThumbnail(
            thumbnails = emptyMap(),
            key = "first",
            thumbnail = first,
            maxEntries = 2,
        )
        thumbnails = putBoundedDirectoryVideoThumbnail(
            thumbnails = thumbnails,
            key = "second",
            thumbnail = second,
            maxEntries = 2,
        )
        thumbnails = putBoundedDirectoryVideoThumbnail(
            thumbnails = thumbnails,
            key = "first",
            thumbnail = first.copy(artworkRevision = 1L),
            maxEntries = 2,
        )
        thumbnails = putBoundedDirectoryVideoThumbnail(
            thumbnails = thumbnails,
            key = "third",
            thumbnail = third,
            maxEntries = 2,
        )

        assertEquals(listOf("first", "third"), thumbnails.keys.toList())
        assertEquals(1L, thumbnails["first"]?.artworkRevision)
    }

    @Test
    fun nameSortingIsCaseInsensitive() {
        val entries = listOf(
            SortableEntry("Beta.cbz"),
            SortableEntry("alpha.cbz"),
        )

        val sorted = filterAndSortDirectoryEntries(
            entries = entries,
            query = "",
            sortField = DirectorySortField.NAME,
            sortDirection = DirectorySortDirection.ASCENDING,
            nameOf = SortableEntry::name,
            sizeOf = SortableEntry::size,
        )

        assertEquals(listOf("alpha.cbz", "Beta.cbz"), sorted.map { it.name })
    }

    @Test
    fun searchMatchesNamesOnlyWithinTheProvidedCurrentDirectoryEntries() {
        val currentDirectoryEntries = listOf(
            SortableEntry("Matching folder", isDirectory = true),
            SortableEntry("matching-book.cbz"),
            SortableEntry("other.pdf"),
        )

        val visible = sorted(currentDirectoryEntries, query = "MATCHING")

        assertEquals(listOf("Matching folder", "matching-book.cbz"), visible.map { it.name })
    }

    @Test
    fun nameSortingUsesNumericValueAndDoesNotPrioritizeDirectories() {
        val entries = listOf(
            SortableEntry("100", isDirectory = true),
            SortableEntry("2"),
            SortableEntry("1"),
        )

        val visible = sorted(entries)

        assertEquals(listOf("1", "2", "100"), visible.map { it.name })
    }

    @Test
    fun sizeSortingTreatsFoldersLikeOtherEntriesWithUnknownSizes() {
        val entries = listOf(
            SortableEntry("large.cbz", size = 500L),
            SortableEntry("unknown.pdf"),
            SortableEntry("small.mkv", size = 10L),
            SortableEntry("Folder", isDirectory = true),
        )

        val visible = sorted(entries, sortField = DirectorySortField.SIZE)

        assertEquals(
            listOf("small.mkv", "large.cbz", "Folder", "unknown.pdf"),
            visible.map { it.name },
        )
    }

    @Test
    fun fileTypeSortingUsesFileExtensions() {
        val entries = listOf(
            SortableEntry("video.mkv"),
            SortableEntry("document.pdf"),
            SortableEntry("archive.cbz"),
        )

        val visible = sorted(entries, sortField = DirectorySortField.TYPE)

        assertEquals(listOf("archive.cbz", "video.mkv", "document.pdf"), visible.map { it.name })
    }

    private fun sorted(
        entries: List<SortableEntry>,
        query: String = "",
        sortField: DirectorySortField = DirectorySortField.NAME,
        sortDirection: DirectorySortDirection = DirectorySortDirection.ASCENDING,
    ): List<SortableEntry> = filterAndSortDirectoryEntries(
        entries = entries,
        query = query,
        sortField = sortField,
        sortDirection = sortDirection,
        nameOf = SortableEntry::name,
        sizeOf = SortableEntry::size,
    )

    private data class SortableEntry(
        val name: String,
        val isDirectory: Boolean = false,
        val size: Long? = null,
    )
}
