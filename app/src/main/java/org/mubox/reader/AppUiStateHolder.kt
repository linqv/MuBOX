package org.mubox.reader

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import org.mubox.reader.core.model.cache.ComicCacheAnalysis

@Stable
internal class AppUiStateHolder(
    val readerOpenState: MutableState<Boolean>,
    val readerLandscapeModeState: MutableState<Boolean>,
    val readerLandscapeOrientationLockedState: MutableState<Boolean>,
    val forceMainPortraitState: MutableState<Boolean>,
    isWebDavOpenState: MutableState<Boolean>,
    isAddingWebDavPathState: MutableState<Boolean>,
    editingWebDavSourceIdState: MutableState<Long?>,
    selectedTabNameState: MutableState<String>,
    selectionState: MutableState<AppSelection>,
    homeSelectionState: MutableState<HomeSelection>,
    localOpenErrorState: MutableState<String?>,
    webDavActionMessageState: MutableState<String?>,
    cacheAnalysisState: MutableState<ComicCacheAnalysis>,
    cacheActionMessageState: MutableState<String?>,
    dataFolderUriTextState: MutableState<String?>,
    isDataFolderLoadingState: MutableState<Boolean>,
) {
    var isWebDavOpen by isWebDavOpenState
    var isAddingWebDavPath by isAddingWebDavPathState
    var editingWebDavSourceId by editingWebDavSourceIdState
    var selectedTabName by selectedTabNameState
    var selection by selectionState
    var homeSelection by homeSelectionState
    var localOpenError by localOpenErrorState
    var webDavActionMessage by webDavActionMessageState
    var cacheAnalysis by cacheAnalysisState
    var cacheActionMessage by cacheActionMessageState
    var dataFolderUriText by dataFolderUriTextState
    var isDataFolderLoading by isDataFolderLoadingState

    val selectedTab: AppTab
        get() = runCatching { AppTab.valueOf(selectedTabName) }.getOrDefault(AppTab.HOME)

    val selectedWebDavFile get() = selection.webDavFileOrNull
    val selectedDirectoryComic get() = selection.directoryComicOrNull
    val selectedDirectoryVideo get() = selection.directoryVideoOrNull
    val hasActiveSelection: Boolean get() = selection.isActive
        || homeSelection.isActive

    fun clearSelection() {
        selection = selection.clear()
        homeSelection = HomeSelection()
    }

    fun toggleHomeHistorySelection(mediaKey: String) {
        homeSelection = homeSelection.toggleHistory(mediaKey)
    }

    fun toggleHomeLibrarySelection(id: Long) {
        homeSelection = homeSelection.toggleLibrary(id)
    }

    fun toggleHomeVideoLibrarySelection(id: Long) {
        homeSelection = homeSelection.toggleVideoLibrary(id)
    }

    fun clearSelectionIf(predicate: (AppSelection) -> Boolean) {
        selection = selection.clearIf(predicate)
    }

    fun selectTab(tab: AppTab, clearTransientMessages: Boolean = true) {
        selectedTabName = tab.name
        if (clearTransientMessages) {
            localOpenError = null
            webDavActionMessage = null
        }
        clearSelection()
    }

    fun returnToHome() {
        selectTab(AppTab.HOME, clearTransientMessages = true)
    }

    fun onDataFolderSelected(uriText: String) {
        dataFolderUriText = uriText
    }
}

@Composable
internal fun rememberAppUiStateHolder(context: Context): AppUiStateHolder {
    val readerOpenState = rememberSaveable { mutableStateOf(false) }
    val readerLandscapeModeState = rememberSaveable { mutableStateOf(false) }
    val readerLandscapeOrientationLockedState = rememberSaveable { mutableStateOf(false) }
    val forceMainPortraitState = rememberSaveable { mutableStateOf(false) }
    val isWebDavOpenState = rememberSaveable { mutableStateOf(false) }
    val isAddingWebDavPathState = rememberSaveable { mutableStateOf(false) }
    val editingWebDavSourceIdState = rememberSaveable { mutableStateOf<Long?>(null) }
    val selectedTabNameState = rememberSaveable { mutableStateOf(AppTab.HOME.name) }
    val selectionState = remember { mutableStateOf<AppSelection>(AppSelection.None) }
    val homeSelectionState = remember { mutableStateOf(HomeSelection()) }
    val localOpenErrorState = remember { mutableStateOf<String?>(null) }
    val webDavActionMessageState = remember { mutableStateOf<String?>(null) }
    val cacheAnalysisState = remember { mutableStateOf(ComicCacheAnalysis()) }
    val cacheActionMessageState = remember { mutableStateOf<String?>(null) }
    val dataFolderUriTextState = rememberSaveable { mutableStateOf<String?>(null) }
    val isDataFolderLoadingState = remember { mutableStateOf(true) }

    return remember(
        readerOpenState,
        readerLandscapeModeState,
        readerLandscapeOrientationLockedState,
        forceMainPortraitState,
        isWebDavOpenState,
        isAddingWebDavPathState,
        editingWebDavSourceIdState,
        selectedTabNameState,
        selectionState,
        homeSelectionState,
        localOpenErrorState,
        webDavActionMessageState,
        cacheAnalysisState,
        cacheActionMessageState,
        dataFolderUriTextState,
        isDataFolderLoadingState,
    ) {
        AppUiStateHolder(
            readerOpenState = readerOpenState,
            readerLandscapeModeState = readerLandscapeModeState,
            readerLandscapeOrientationLockedState = readerLandscapeOrientationLockedState,
            forceMainPortraitState = forceMainPortraitState,
            isWebDavOpenState = isWebDavOpenState,
            isAddingWebDavPathState = isAddingWebDavPathState,
            editingWebDavSourceIdState = editingWebDavSourceIdState,
            selectedTabNameState = selectedTabNameState,
            selectionState = selectionState,
            homeSelectionState = homeSelectionState,
            localOpenErrorState = localOpenErrorState,
            webDavActionMessageState = webDavActionMessageState,
            cacheAnalysisState = cacheAnalysisState,
            cacheActionMessageState = cacheActionMessageState,
            dataFolderUriTextState = dataFolderUriTextState,
            isDataFolderLoadingState = isDataFolderLoadingState,
        )
    }
}
