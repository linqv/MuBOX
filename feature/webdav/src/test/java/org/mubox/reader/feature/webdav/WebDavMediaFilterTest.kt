package org.mubox.reader.feature.webdav

import org.mubox.reader.core.remote.WebDavItem
import org.mubox.reader.core.model.media.MediaKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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
    fun longPressActionsExposeMediaSpecificActionsForComicsAndVideos() {
        assertEquals(
            listOf(WebDavFileMenuAction.AddToLibrary, WebDavFileMenuAction.DownloadToLocal),
            webDavItemLongPressActions(item("comic.cbz")),
        )
        assertEquals(
            listOf(WebDavFileMenuAction.AddToVideoLibrary, WebDavFileMenuAction.DownloadToLocal),
            webDavItemLongPressActions(item("movie.mp4")),
        )
        assertEquals(emptyList<WebDavFileMenuAction>(), webDavItemLongPressActions(item("folder", isDirectory = true)))
    }

    @Test
    fun webDavItemMediaKindUsesDirectoryAndExtension() {
        assertEquals(MediaKind.Directory, item("folder", isDirectory = true).mediaKind)
        assertEquals(MediaKind.Video, item("movie.webm").mediaKind)
        assertEquals(MediaKind.Unknown, item("notes.txt").mediaKind)
    }

    @Test
    fun accountFormOnlyShowsWhenAddingOrEditingWebDavSource() {
        assertTrue(
            shouldShowWebDavAccountForm(
                isAddingWebDavPath = true,
                editingWebDavSourceId = null,
                webDavStatus = WEB_DAV_STATUS_NOT_CONNECTED,
            ),
        )
        assertTrue(
            shouldShowWebDavAccountForm(
                isAddingWebDavPath = false,
                editingWebDavSourceId = 7L,
                webDavStatus = WEB_DAV_STATUS_NOT_CONNECTED,
            ),
        )
        assertFalse(
            shouldShowWebDavAccountForm(
                isAddingWebDavPath = false,
                editingWebDavSourceId = null,
                webDavStatus = WEB_DAV_STATUS_NOT_CONNECTED,
            ),
        )
        assertFalse(
            shouldShowWebDavAccountForm(
                isAddingWebDavPath = false,
                editingWebDavSourceId = null,
                webDavStatus = WEB_DAV_STATUS_CONNECTED,
            ),
        )
    }

    @Test
    fun buildWebDavBaseUrlConstructsExpectedUrls() {
        assertEquals(
            "https://example.com/",
            buildWebDavBaseUrl(useHttps = true, host = "example.com", port = "443", rootPath = "/"),
        )
        assertEquals(
            "http://example.com:8080/dav",
            buildWebDavBaseUrl(useHttps = false, host = "example.com", port = "8080", rootPath = "dav"),
        )
        assertEquals(
            "http://192.168.1.20/",
            buildWebDavBaseUrl(useHttps = false, host = "192.168.1.20", port = "80", rootPath = "/"),
        )
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
