package com.example.comicdav.feature.filedirectory

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FileDirectoryScreenBehaviorTest {
    @Test
    fun entryClickOpensDirectoriesAndReadsComics() {
        assertEquals(
            FileDirectoryEntryClickAction.OpenDirectory,
            fileDirectoryEntryClickAction(
                FileDirectoryBrowserItem("Series", "content://tree/comics/series", isDirectory = true),
            ),
        )
        assertEquals(
            FileDirectoryEntryClickAction.OpenComic,
            fileDirectoryEntryClickAction(
                FileDirectoryBrowserItem("book.cbz", "content://tree/comics/book", isDirectory = false),
            ),
        )
    }

    @Test
    fun longPressActionsAreOnlyShownForComicFiles() {
        assertTrue(
            fileDirectoryEntryLongPressActions(
                FileDirectoryBrowserItem("Series", "content://tree/comics/series", isDirectory = true),
            ).isEmpty(),
        )
        assertEquals(
            listOf(FileDirectoryEntryMenuAction.AddToLibrary),
            fileDirectoryEntryLongPressActions(
                FileDirectoryBrowserItem("book.cbz", "content://tree/comics/book", isDirectory = false),
            ),
        )
    }

    @Test
    fun directoryEntriesDoNotShowContinueBrowsingHint() {
        assertEquals(
            "",
            fileDirectoryEntrySupportingLabel(
                FileDirectoryBrowserItem("Series", "content://tree/comics/series", isDirectory = true),
            ),
        )
    }

    @Test
    fun comicEntriesOnlyShowSizeMetadata() {
        assertEquals(
            "4 KiB",
            fileDirectoryEntrySupportingLabel(
                FileDirectoryBrowserItem("book.cbz", "content://tree/comics/book", isDirectory = false, size = 4096L),
            ),
        )
        assertEquals(
            "大小未知",
            fileDirectoryEntrySupportingLabel(
                FileDirectoryBrowserItem("book.cbz", "content://tree/comics/book", isDirectory = false),
            ),
        )
    }
}
