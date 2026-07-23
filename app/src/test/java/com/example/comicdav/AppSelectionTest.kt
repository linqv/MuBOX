package com.example.comicdav

import com.example.comicdav.data.library.LibraryItem
import com.example.comicdav.data.library.LibraryItemWithSources
import com.example.comicdav.data.library.SourceType
import com.example.comicdav.data.videolibrary.VideoLibraryItem
import com.example.comicdav.data.videolibrary.VideoLibraryItemWithSources
import com.example.comicdav.data.videolibrary.VideoSourceType
import com.example.comicdav.feature.filedirectory.FileDirectoryBrowserItem
import com.example.comicdav.core.remote.WebDavItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppSelectionTest {
    private val webDavFile = WebDavItem(
        name = "remote.cbz",
        path = "/remote.cbz",
        isDirectory = false,
        size = 42L,
        etag = "etag",
        lastModified = 7L,
    )
    private val directoryComic = FileDirectoryBrowserItem(
        name = "local.cbz",
        uri = "content://comics/local.cbz",
        isDirectory = false,
    )
    private val directoryVideo = FileDirectoryBrowserItem(
        name = "episode.mkv",
        uri = "content://videos/episode.mkv",
        isDirectory = false,
    )
    private val libraryItem = LibraryItemWithSources(
        item = LibraryItem(
            id = 11L,
            title = "Comic",
            displayName = "Comic.cbz",
            sourceType = SourceType.LOCAL,
            addedAt = 1L,
        ),
        localSource = null,
        webDavSource = null,
    )
    private val videoLibraryItem = VideoLibraryItemWithSources(
        item = VideoLibraryItem(
            id = 12L,
            title = "Episode",
            displayName = "Episode.mkv",
            sourceType = VideoSourceType.LOCAL,
            addedAt = 1L,
        ),
        localSource = null,
        webDavSource = null,
    )

    @Test
    fun eachSelectionVariantProjectsExactlyOneRouteValue() {
        val selections = listOf(
            AppSelection.WebDavFile(webDavFile),
            AppSelection.DirectoryComic(directoryComic),
            AppSelection.DirectoryVideo(directoryVideo),
            AppSelection.LibraryItem(libraryItem),
            AppSelection.VideoLibraryItem(videoLibraryItem),
        )

        selections.forEach { selection ->
            assertTrue(selection.isActive)
            assertEquals(1, selection.projectedValues().count { it != null })
        }
    }

    @Test
    fun switchingSelectionReplacesThePreviousType() {
        var selection: AppSelection = AppSelection.WebDavFile(webDavFile)

        selection = AppSelection.DirectoryVideo(directoryVideo)

        assertNull(selection.webDavFileOrNull)
        assertNull(selection.directoryComicOrNull)
        assertEquals(directoryVideo, selection.directoryVideoOrNull)
        assertNull(selection.libraryItemOrNull)
        assertNull(selection.videoLibraryItemOrNull)
    }

    @Test
    fun conditionalClearDoesNotEraseASelectionChosenAfterTheOperationStarted() {
        var selection: AppSelection = AppSelection.WebDavFile(webDavFile)
        selection = AppSelection.LibraryItem(libraryItem)

        selection = selection.clearIf { it is AppSelection.WebDavFile }

        assertEquals(libraryItem, selection.libraryItemOrNull)
        assertTrue(selection.isActive)
    }

    @Test
    fun clearRemovesEveryRouteProjection() {
        val selection = AppSelection.VideoLibraryItem(videoLibraryItem).clear()

        assertEquals(AppSelection.None, selection)
        assertFalse(selection.isActive)
        selection.projectedValues().forEach(::assertNull)
    }

    private fun AppSelection.projectedValues(): List<Any?> = listOf(
        webDavFileOrNull,
        directoryComicOrNull,
        directoryVideoOrNull,
        libraryItemOrNull,
        videoLibraryItemOrNull,
    )
}
