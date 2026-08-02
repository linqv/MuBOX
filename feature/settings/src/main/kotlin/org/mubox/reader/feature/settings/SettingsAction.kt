package org.mubox.reader.feature.settings

import org.mubox.reader.core.model.cache.ComicCacheCategory
import org.mubox.reader.core.model.history.WatchHistoryEntry
import org.mubox.reader.core.model.settings.AppearanceSettings
import org.mubox.reader.core.model.settings.DiagnosticsSettings
import org.mubox.reader.core.model.settings.HistorySettings
import org.mubox.reader.core.model.settings.ReaderSettings
import org.mubox.reader.core.model.settings.StorageSettings
import org.mubox.reader.core.model.settings.VideoSettings

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
