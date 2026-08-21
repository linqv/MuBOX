package org.mubox.reader

import org.mubox.reader.feature.filedirectory.FileDirectoryBrowserItem
import org.mubox.reader.core.remote.WebDavItem
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

    @Test
    fun eachSelectionVariantProjectsExactlyOneRouteValue() {
        val selections = listOf(
            AppSelection.WebDavFile(webDavFile),
            AppSelection.DirectoryComic(directoryComic),
            AppSelection.DirectoryVideo(directoryVideo),
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
    }

    @Test
    fun conditionalClearDoesNotEraseASelectionChosenAfterTheOperationStarted() {
        var selection: AppSelection = AppSelection.WebDavFile(webDavFile)
        selection = AppSelection.DirectoryComic(directoryComic)

        selection = selection.clearIf { it is AppSelection.WebDavFile }

        assertEquals(directoryComic, selection.directoryComicOrNull)
        assertTrue(selection.isActive)
    }

    @Test
    fun clearRemovesEveryRouteProjection() {
        val selection = AppSelection.DirectoryVideo(directoryVideo).clear()

        assertEquals(AppSelection.None, selection)
        assertFalse(selection.isActive)
        selection.projectedValues().forEach(::assertNull)
    }

    @Test
    fun homeSelectionSupportsCrossGroupMultiSelect() {
        val selection = HomeSelection()
            .toggleHistory("history-1")
            .toggleLibrary(11L)
            .toggleVideoLibrary(12L)

        assertTrue(selection.isActive)
        assertEquals(3, selection.count)
        assertEquals(setOf("history-1"), selection.historyKeys)
        assertEquals(setOf(11L), selection.libraryItemIds)
        assertEquals(setOf(12L), selection.videoLibraryItemIds)
    }

    @Test
    fun homeSelectionCanSelectAllHistoryWithoutChangingOtherGroups() {
        val selection = HomeSelection(
            historyKeys = setOf("history-1"),
            libraryItemIds = setOf(11L),
        ).selectAllHistory(setOf("history-1", "history-2", "history-3"))

        assertEquals(setOf("history-1", "history-2", "history-3"), selection.historyKeys)
        assertEquals(setOf(11L), selection.libraryItemIds)
        assertEquals(4, selection.count)
    }

    @Test
    fun homeSelectionToggleRemovesOnlyTheTappedItem() {
        val selection = HomeSelection(
            historyKeys = setOf("history-1"),
            libraryItemIds = setOf(11L),
            videoLibraryItemIds = setOf(12L),
        ).toggleLibrary(11L)

        assertTrue(selection.isActive)
        assertEquals(2, selection.count)
        assertTrue(selection.libraryItemIds.isEmpty())
        assertEquals(setOf("history-1"), selection.historyKeys)
        assertEquals(setOf(12L), selection.videoLibraryItemIds)
    }

    private fun AppSelection.projectedValues(): List<Any?> = listOf(
        webDavFileOrNull,
        directoryComicOrNull,
        directoryVideoOrNull,
    )
}
