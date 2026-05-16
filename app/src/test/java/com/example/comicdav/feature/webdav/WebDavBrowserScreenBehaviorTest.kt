package com.example.comicdav.feature.webdav

import com.example.comicdav.network.WebDavItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WebDavBrowserScreenBehaviorTest {
    @Test
    fun itemClickOpensDirectoriesAndReadsComics() {
        assertEquals(
            WebDavItemClickAction.OpenDirectory,
            webDavItemClickAction(
                WebDavItem("Series", "/Series/", isDirectory = true, size = null, etag = null, lastModified = null),
            ),
        )
        assertEquals(
            WebDavItemClickAction.OpenComic,
            webDavItemClickAction(
                WebDavItem("book.cbz", "/book.cbz", isDirectory = false, size = 12L, etag = "a", lastModified = null),
            ),
        )
    }

    @Test
    fun longPressFileActionsMoveLibraryAndDownloadOutOfTheRow() {
        val actions = webDavItemLongPressActions(
            WebDavItem("book.cbz", "/book.cbz", isDirectory = false, size = 12L, etag = "a", lastModified = null),
        )

        assertEquals(
            listOf(WebDavFileMenuAction.AddToLibrary, WebDavFileMenuAction.DownloadToLocal),
            actions,
        )
        assertFalse(actions.any { it.name.contains("Diagnostic", ignoreCase = true) })
    }

    @Test
    fun longPressActionsAreHiddenForDirectories() {
        assertTrue(
            webDavItemLongPressActions(
                WebDavItem("Series", "/Series/", isDirectory = true, size = null, etag = null, lastModified = null),
            ).isEmpty(),
        )
    }
}
