package com.example.comicdav

import com.example.comicdav.core.model.cache.ComicCacheCategory
import com.example.comicdav.core.model.history.WatchHistoryEntry
import com.example.comicdav.data.AppSettingsStore
import com.example.comicdav.feature.settings.SettingsAction
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

internal class SettingsActionHandler(
    private val appSettingsStore: AppSettingsStore,
    private val scope: CoroutineScope,
    private val onClearCacheCategory: (ComicCacheCategory) -> Unit,
    private val onClearAllCache: () -> Unit,
    private val onDeleteHistoryEntry: (WatchHistoryEntry) -> Unit = {},
    private val onClearHistory: () -> Unit = {},
) {
    fun handle(action: SettingsAction) {
        when (action) {
            is SettingsAction.UpdateReader ->
                scope.launch { appSettingsStore.updateReaderSettings(action.transform) }
            is SettingsAction.UpdateAppearance ->
                scope.launch { appSettingsStore.updateAppearanceSettings(action.transform) }
            is SettingsAction.UpdateStorage ->
                scope.launch { appSettingsStore.updateStorageSettings(action.transform) }
            is SettingsAction.UpdateVideo ->
                scope.launch { appSettingsStore.updateVideoSettings(action.transform) }
            is SettingsAction.UpdateHistory ->
                scope.launch { appSettingsStore.updateHistorySettings(action.transform) }
            is SettingsAction.UpdateDiagnostics ->
                scope.launch { appSettingsStore.updateDiagnosticsSettings(action.transform) }
            is SettingsAction.DeleteHistoryEntry -> onDeleteHistoryEntry(action.entry)
            SettingsAction.ClearHistory -> onClearHistory()
            is SettingsAction.ClearCacheCategory -> onClearCacheCategory(action.category)
            SettingsAction.ClearAllCache -> onClearAllCache()
        }
    }
}
