package com.example.comicdav.feature.filedirectory

import com.example.comicdav.data.filedirectory.FileDirectorySourceEntity
import com.example.comicdav.data.filedirectory.FileDirectorySourceType
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

    @Test
    fun webDavSourceSubtitleDecodesPercentEncodedPath() {
        val source = FileDirectorySourceEntity(
            displayName = "漫画",
            sourceType = FileDirectorySourceType.WEBDAV,
            webDavPath = "/%E6%BC%AB%E7%94%BB/%E8%A7%86%E9%A2%91/",
            addedAt = 100L,
        )

        assertEquals("/漫画/视频/", fileDirectorySourceSubtitle(source))
    }
}
