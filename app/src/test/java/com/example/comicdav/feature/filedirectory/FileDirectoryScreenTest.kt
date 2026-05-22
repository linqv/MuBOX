package com.example.comicdav.feature.filedirectory

import org.junit.Assert.assertEquals
import org.junit.Test

class FileDirectoryScreenTest {
    @Test
    fun comicLongPressActionsAddToLibrary() {
        val comic = FileDirectoryBrowserItem(
            name = "chapter.cbz",
            uri = "content://comic/chapter.cbz",
            isDirectory = false,
        )

        assertEquals(
            listOf(FileDirectoryEntryMenuAction.AddToLibrary),
            fileDirectoryEntryLongPressActions(comic),
        )
    }

    @Test
    fun videoLongPressActionsAddToVideoLibrary() {
        val video = FileDirectoryBrowserItem(
            name = "movie.mp4",
            uri = "content://video/movie.mp4",
            isDirectory = false,
        )

        assertEquals(
            listOf(FileDirectoryEntryMenuAction.AddToVideoLibrary),
            fileDirectoryEntryLongPressActions(video),
        )
    }
}
