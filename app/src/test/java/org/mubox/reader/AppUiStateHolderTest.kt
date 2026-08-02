package org.mubox.reader

import androidx.compose.runtime.mutableStateOf
import org.mubox.reader.core.model.cache.ComicCacheAnalysis
import org.mubox.reader.core.remote.WebDavItem
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

        ui.selectTab(AppTab.SETTINGS)

        assertEquals(AppTab.SETTINGS, ui.selectedTab)
        assertEquals(AppSelection.None, ui.selection)
        assertNull(ui.localOpenError)
        assertNull(ui.webDavActionMessage)
    }

    @Test
    fun choosingDataFolderUpdatesDataLocation() {
        val ui = stateHolder()

        ui.onDataFolderSelected("content://documents/mubox")

        assertEquals("content://documents/mubox", ui.dataFolderUriText)
    }

    private fun stateHolder() = AppUiStateHolder(
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
        dataFolderUriTextState = mutableStateOf<String?>(null),
        isDataFolderLoadingState = mutableStateOf(true),
    )
}
