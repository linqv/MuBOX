package com.example.comicdav.feature.directorylisting

import org.junit.Assert.assertEquals
import org.junit.Test

class DirectoryListingControlsTest {
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
            isDirectory = SortableEntry::isDirectory,
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
    fun sizeSortingKeepsFoldersFirstAndUnknownSizesLast() {
        val entries = listOf(
            SortableEntry("large.cbz", size = 500L),
            SortableEntry("unknown.pdf"),
            SortableEntry("small.mkv", size = 10L),
            SortableEntry("Folder", isDirectory = true),
        )

        val visible = sorted(entries, sortField = DirectorySortField.SIZE)

        assertEquals(
            listOf("Folder", "small.mkv", "large.cbz", "unknown.pdf"),
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
        isDirectory = SortableEntry::isDirectory,
        sizeOf = SortableEntry::size,
    )

    private data class SortableEntry(
        val name: String,
        val isDirectory: Boolean = false,
        val size: Long? = null,
    )
}
