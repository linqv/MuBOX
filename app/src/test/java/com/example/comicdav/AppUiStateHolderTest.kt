package com.example.comicdav

import androidx.compose.runtime.mutableStateOf
import com.example.comicdav.data.ComicCacheAnalysis
import com.example.comicdav.core.remote.WebDavItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AppUiStateHolderTest {
    @Test
    fun selectingTabClearsContextualSelectionAndTransientMessages() {
        val ui = stateHolder()
        ui.selection = AppSelection.WebDavFile(
            WebDavItem(
                name = "remote.cbz",
                path = "/remote.cbz",
                isDirectory = false,
                size = 42L,
                etag = "etag",
                lastModified = 7L,
            ),
        )
        ui.localOpenError = "open failed"
        ui.webDavActionMessage = "saved"

        ui.selectTab(AppTab.LIBRARY)

        assertEquals(AppTab.LIBRARY, ui.selectedTab)
        assertEquals(AppSelection.None, ui.selection)
        assertNull(ui.localOpenError)
        assertNull(ui.webDavActionMessage)
    }

    @Test
    fun choosingFirstDataFolderAlsoSuppliesDefaultLogFolder() {
        val ui = stateHolder()

        ui.onDataFolderSelected("content://documents/mubox")

        assertEquals("content://documents/mubox", ui.dataFolderUriText)
        assertEquals("content://documents/mubox", ui.logFolderUriText)
    }

    @Test
    fun choosingDataFolderDoesNotReplaceExplicitLogFolder() {
        val ui = stateHolder(logFolderUriText = "content://documents/logs")

        ui.onDataFolderSelected("content://documents/mubox")

        assertEquals("content://documents/logs", ui.logFolderUriText)
    }

    private fun stateHolder(logFolderUriText: String? = null) = AppUiStateHolder(
        readerOpenState = mutableStateOf(false),
        readerLandscapeModeState = mutableStateOf(false),
        readerLandscapeOrientationLockedState = mutableStateOf(false),
        forceMainPortraitState = mutableStateOf(false),
        isWebDavOpenState = mutableStateOf(false),
        isAddingWebDavPathState = mutableStateOf(false),
        editingWebDavSourceIdState = mutableStateOf<Long?>(null),
        selectedTabNameState = mutableStateOf(AppTab.SOURCES.name),
        selectionState = mutableStateOf(AppSelection.None),
        localOpenErrorState = mutableStateOf<String?>(null),
        webDavActionMessageState = mutableStateOf<String?>(null),
        cacheAnalysisState = mutableStateOf(ComicCacheAnalysis()),
        cacheActionMessageState = mutableStateOf<String?>(null),
        logFolderUriTextState = mutableStateOf(logFolderUriText),
        dataFolderUriTextState = mutableStateOf<String?>(null),
        isDataFolderLoadingState = mutableStateOf(true),
    )
}
