package com.example.comicdav.feature.settings

import com.example.comicdav.core.model.cache.ComicCacheCategory
import com.example.comicdav.core.model.history.WatchHistoryEntry
import com.example.comicdav.core.model.settings.AppearanceSettings
import com.example.comicdav.core.model.settings.DiagnosticsSettings
import com.example.comicdav.core.model.settings.HistorySettings
import com.example.comicdav.core.model.settings.ReaderSettings
import com.example.comicdav.core.model.settings.StorageSettings
import com.example.comicdav.core.model.settings.VideoSettings

sealed interface SettingsAction {
    class UpdateReader(val transform: (ReaderSettings) -> ReaderSettings) : SettingsAction
    class UpdateAppearance(val transform: (AppearanceSettings) -> AppearanceSettings) : SettingsAction
    class UpdateStorage(val transform: (StorageSettings) -> StorageSettings) : SettingsAction
    class UpdateVideo(val transform: (VideoSettings) -> VideoSettings) : SettingsAction
    class UpdateHistory(val transform: (HistorySettings) -> HistorySettings) : SettingsAction
    class UpdateDiagnostics(val transform: (DiagnosticsSettings) -> DiagnosticsSettings) : SettingsAction
    data class DeleteHistoryEntry(val entry: WatchHistoryEntry) : SettingsAction
    data object ClearHistory : SettingsAction
    data class ClearCacheCategory(val category: ComicCacheCategory) : SettingsAction
    data object ClearAllCache : SettingsAction
}
