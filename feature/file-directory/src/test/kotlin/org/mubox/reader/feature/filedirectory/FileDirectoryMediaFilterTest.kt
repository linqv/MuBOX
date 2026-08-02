package org.mubox.reader.feature.filedirectory

import org.mubox.reader.core.model.media.MediaKind
import org.junit.Assert.assertEquals
import org.junit.Test

class FileDirectoryMediaFilterTest {
    @Test
    fun filterBrowsableLocalDirectoryItemsIncludesVideoAndSubtitlesButHidesUnsupportedFiles() {
        val items = listOf(
            item(name = "folder", isDirectory = true),
            item(name = "comic.cbz"),
            item(name = "movie.MKV"),
            item(name = "subtitle.srt"),
            item(name = "song.mp3"),
            item(name = "notes.txt"),
        )

        val result = filterBrowsableLocalDirectoryItems(items)

        assertEquals(
            listOf("folder", "comic.cbz", "movie.MKV", "subtitle.srt"),
            result.map { it.name },
        )
    }

    @Test
    fun entryClickActionRoutesVideoSeparatelyFromComics() {
        assertEquals(FileDirectoryEntryClickAction.OpenDirectory, fileDirectoryEntryClickAction(item("folder", true)))
        assertEquals(FileDirectoryEntryClickAction.OpenComic, fileDirectoryEntryClickAction(item("comic.cbz")))
        assertEquals(FileDirectoryEntryClickAction.OpenVideo, fileDirectoryEntryClickAction(item("movie.mp4")))
        assertEquals(FileDirectoryEntryClickAction.NoAction, fileDirectoryEntryClickAction(item("movie.srt")))
    }

    @Test
    fun longPressActionsExposeLibraryActionsForComicsAndVideos() {
        assertEquals(
            listOf(FileDirectoryEntryMenuAction.AddToLibrary),
            fileDirectoryEntryLongPressActions(item("comic.cbz")),
        )
        assertEquals(
            listOf(FileDirectoryEntryMenuAction.AddToVideoLibrary),
            fileDirectoryEntryLongPressActions(item("movie.mp4")),
        )
        assertEquals(emptyList<FileDirectoryEntryMenuAction>(), fileDirectoryEntryLongPressActions(item("folder", true)))
    }

    @Test
    fun fileDirectoryBrowserItemDefaultsMediaKindFromName() {
        assertEquals(MediaKind.Directory, item("folder", true).mediaKind)
        assertEquals(MediaKind.Video, item("movie.webm").mediaKind)
        assertEquals(MediaKind.Unknown, item("notes.txt").mediaKind)
    }

    private fun item(name: String, isDirectory: Boolean = false): FileDirectoryBrowserItem =
        FileDirectoryBrowserItem(
            name = name,
            uri = "content://local/$name",
            isDirectory = isDirectory,
            size = if (isDirectory) null else 1024,
            lastModified = null,
        )
}
