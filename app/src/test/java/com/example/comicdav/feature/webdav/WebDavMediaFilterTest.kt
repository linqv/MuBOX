package com.example.comicdav.feature.webdav

import com.example.comicdav.network.WebDavItem
import com.example.comicdav.video.MediaKind
import org.junit.Assert.assertEquals
import org.junit.Test

class WebDavMediaFilterTest {
    @Test
    fun filterBrowsableWebDavItemsIncludesVideoAndSubtitlesButHidesUnsupportedFiles() {
        val items = listOf(
            item(name = "folder", isDirectory = true),
            item(name = "comic.cbz"),
            item(name = "movie.MKV"),
            item(name = "subtitle.srt"),
            item(name = "song.mp3"),
            item(name = "notes.txt"),
        )

        val result = filterBrowsableWebDavItems(items)

        assertEquals(
            listOf("folder", "comic.cbz", "movie.MKV", "subtitle.srt"),
            result.map { it.name },
        )
    }

    @Test
    fun itemClickActionRoutesVideoSeparatelyFromComics() {
        assertEquals(WebDavItemClickAction.OpenDirectory, webDavItemClickAction(item("folder", isDirectory = true)))
        assertEquals(WebDavItemClickAction.OpenComic, webDavItemClickAction(item("comic.zip")))
        assertEquals(WebDavItemClickAction.OpenVideo, webDavItemClickAction(item("movie.mp4")))
        assertEquals(WebDavItemClickAction.NoAction, webDavItemClickAction(item("movie.srt")))
    }

    @Test
    fun longPressActionsOnlyExposeComicLibraryActionsForComics() {
        assertEquals(
            listOf(WebDavFileMenuAction.AddToLibrary, WebDavFileMenuAction.DownloadToLocal),
            webDavItemLongPressActions(item("comic.cbz")),
        )
        assertEquals(emptyList<WebDavFileMenuAction>(), webDavItemLongPressActions(item("movie.mp4")))
        assertEquals(emptyList<WebDavFileMenuAction>(), webDavItemLongPressActions(item("folder", isDirectory = true)))
    }

    @Test
    fun webDavItemMediaKindUsesDirectoryAndExtension() {
        assertEquals(MediaKind.Directory, item("folder", isDirectory = true).mediaKind)
        assertEquals(MediaKind.Video, item("movie.webm").mediaKind)
        assertEquals(MediaKind.Unknown, item("notes.txt").mediaKind)
    }

    private fun item(name: String, isDirectory: Boolean = false): WebDavItem =
        WebDavItem(
            name = name,
            path = if (isDirectory) "/$name/" else "/$name",
            isDirectory = isDirectory,
            size = if (isDirectory) null else 1024,
            etag = null,
            lastModified = null,
        )
}
