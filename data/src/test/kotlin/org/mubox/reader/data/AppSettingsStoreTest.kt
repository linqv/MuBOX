package org.mubox.reader.data

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import org.mubox.reader.core.model.settings.Anime4KProfile
import org.mubox.reader.core.model.settings.AppColorPalette
import org.mubox.reader.core.model.settings.AppearanceSettings
import org.mubox.reader.core.model.settings.DiagnosticLogLevel
import org.mubox.reader.core.model.settings.DiagnosticsSettings
import org.mubox.reader.core.model.settings.GpuApiMode
import org.mubox.reader.core.model.settings.HistorySettings
import org.mubox.reader.core.model.settings.MpvProfileMode
import org.mubox.reader.core.model.settings.ReaderSettings
import org.mubox.reader.core.model.settings.ReadingDirection
import org.mubox.reader.core.model.settings.StorageSettings
import org.mubox.reader.core.model.settings.VideoBackgroundMode
import org.mubox.reader.core.model.settings.VideoDecoderMode
import org.mubox.reader.core.model.settings.VideoForwardPrefetchMode
import org.mubox.reader.core.model.settings.VideoOutputMode
import org.mubox.reader.core.model.settings.VideoPlayerOrientationMode
import org.mubox.reader.core.model.settings.VideoSettings
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AppSettingsStoreTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun defaultsEnableVideoSeekOptimizationWithStandardPrefetch() = runTest {
        val store = createStore("defaults.preferences_pb")

        val settings = store.settings.first()

        assertTrue(settings.video.videoSeekOptimizationEnabled)
        assertEquals(VideoForwardPrefetchMode.STANDARD, settings.video.videoForwardPrefetchMode)
        assertFalse(settings.video.videoPlayerProxyDebugInfoEnabled)
        assertEquals(MpvProfileMode.FAST, settings.video.mpvProfileMode)
        assertTrue(settings.video.gridVideoThumbnailsEnabled)
        assertEquals(90, settings.history.historyRetentionDays)
        assertEquals(200, settings.history.historyMaxRecords)
        assertEquals(DiagnosticLogLevel.ERROR, settings.diagnostics.logLevel)
    }

    @Test
    fun diagnosticLogLevelCanDisableAllLogsAndBeReadBack() = runTest {
        val store = createStore("diagnostic_log_level.preferences_pb")

        store.updateDiagnosticsSettings { DiagnosticsSettings(DiagnosticLogLevel.OFF) }

        assertEquals(DiagnosticLogLevel.OFF, store.settings.first().diagnostics.logLevel)
    }

    @Test
    fun legacyReaderLoggingOffMigratesToAllLogsOff() = runTest {
        val preferencesFile = temporaryFolder.newFile("legacy_reader_logging_off.preferences_pb")
        val dataStore = PreferenceDataStoreFactory.create(
            scope = backgroundScope,
            produceFile = { preferencesFile },
        )
        dataStore.edit { preferences ->
            preferences[stringPreferencesKey("reader_logging_mode")] = "OFF"
        }

        val settings = AppSettingsStore(dataStore).settings.first()

        assertEquals(DiagnosticLogLevel.OFF, settings.diagnostics.logLevel)
    }

    @Test
    fun legacyDisabledLoggingFlagMigratesToAllLogsOff() = runTest {
        val preferencesFile = temporaryFolder.newFile("legacy_logging_flag_off.preferences_pb")
        val dataStore = PreferenceDataStoreFactory.create(
            scope = backgroundScope,
            produceFile = { preferencesFile },
        )
        dataStore.edit { preferences ->
            preferences[booleanPreferencesKey("logging_enabled")] = false
        }

        val settings = AppSettingsStore(dataStore).settings.first()

        assertEquals(DiagnosticLogLevel.OFF, settings.diagnostics.logLevel)
    }

    @Test
    fun videoProxySettingsCanBeUpdatedAndReadBack() = runTest {
        val store = createStore("updates.preferences_pb")

        store.updateVideoSettings { video ->
            video.copy(
                videoSeekOptimizationEnabled = false,
                videoForwardPrefetchMode = VideoForwardPrefetchMode.AGGRESSIVE,
                videoPlayerProxyDebugInfoEnabled = true,
            )
        }

        val settings = store.settings.first()

        assertFalse(settings.video.videoSeekOptimizationEnabled)
        assertEquals(VideoForwardPrefetchMode.AGGRESSIVE, settings.video.videoForwardPrefetchMode)
        assertTrue(settings.video.videoPlayerProxyDebugInfoEnabled)
    }

    @Test
    fun mpvProfileModeCanBeUpdatedAndReadBack() = runTest {
        val store = createStore("mpv_profile.preferences_pb")

        store.updateVideoSettings { video -> video.copy(mpvProfileMode = MpvProfileMode.HIGH_QUALITY) }

        val settings = store.settings.first()

        assertEquals(MpvProfileMode.HIGH_QUALITY, settings.video.mpvProfileMode)
    }

    @Test
    fun anime4kDefaultsToOff() = runTest {
        val store = createStore("anime4k_defaults.preferences_pb")

        val settings = store.settings.first()

        assertEquals(Anime4KProfile.OFF, settings.video.anime4kProfile)
    }

    @Test
    fun anime4kProfileCanBeUpdatedAndReadBack() = runTest {
        val store = createStore("anime4k_updates.preferences_pb")

        store.updateVideoSettings { video -> video.copy(anime4kProfile = Anime4KProfile.AUTO) }

        val settings = store.settings.first()
        assertEquals(Anime4KProfile.AUTO, settings.video.anime4kProfile)
    }

    @Test
    fun legacyAnime4kSettingsMigrateToClosestProfile() = runTest {
        val preferencesFile = temporaryFolder.newFile("anime4k_legacy.preferences_pb")
        val dataStore = PreferenceDataStoreFactory.create(
            scope = backgroundScope,
            produceFile = { preferencesFile },
        )
        dataStore.edit { preferences ->
            preferences[booleanPreferencesKey("anime4k_enabled")] = true
            preferences[stringPreferencesKey("anime4k_mode")] = "C_PLUS"
            preferences[stringPreferencesKey("anime4k_quality")] = "HIGH"
        }

        val settings = AppSettingsStore(dataStore).settings.first()

        assertEquals(Anime4KProfile.EXTREME, settings.video.anime4kProfile)
    }

    @Test
    fun existingFlatPreferenceKeysPopulateGroupedSettings() = runTest {
        val preferencesFile = temporaryFolder.newFile("flat_keys.preferences_pb")
        val dataStore = PreferenceDataStoreFactory.create(
            scope = backgroundScope,
            produceFile = { preferencesFile },
        )
        dataStore.edit { preferences ->
            preferences[stringPreferencesKey("reading_direction")] = ReadingDirection.RIGHT_TO_LEFT.name
            preferences[stringPreferencesKey("color_palette")] = AppColorPalette.SEPIA.name
            preferences[intPreferencesKey("disk_cache_limit_gb")] = 2048
            preferences[booleanPreferencesKey("video_resume_enabled")] = false
            preferences[intPreferencesKey("history_retention_days")] = 180
        }

        val settings = AppSettingsStore(dataStore).settings.first()

        assertEquals(ReadingDirection.RIGHT_TO_LEFT, settings.reader.readingDirection)
        assertEquals(AppColorPalette.SEPIA, settings.appearance.colorPalette)
        assertEquals(2048, settings.storage.diskCacheLimitMb)
        assertFalse(settings.video.videoResumeEnabled)
        assertEquals(180, settings.history.historyRetentionDays)
    }

    @Test
    fun groupedUpdatesKeepWritingExistingPreferenceKeys() = runTest {
        val preferencesFile = temporaryFolder.newFile("grouped_keys.preferences_pb")
        val dataStore = PreferenceDataStoreFactory.create(
            scope = backgroundScope,
            produceFile = { preferencesFile },
        )
        val store = AppSettingsStore(dataStore)

        store.updateReaderSettings { ReaderSettings(readingDirection = ReadingDirection.VERTICAL) }
        store.updateAppearanceSettings { AppearanceSettings(colorPalette = AppColorPalette.NIGHT) }
        store.updateStorageSettings { StorageSettings(diskCacheLimitMb = 3072) }
        store.updateVideoSettings { VideoSettings(videoResumeEnabled = false) }
        store.updateHistorySettings { HistorySettings(historyMaxRecords = 500) }
        store.updateDiagnosticsSettings { DiagnosticsSettings(DiagnosticLogLevel.OFF) }

        val preferences = dataStore.data.first()
        assertEquals(
            ReadingDirection.VERTICAL.name,
            preferences[stringPreferencesKey("reading_direction")],
        )
        assertEquals(AppColorPalette.NIGHT.name, preferences[stringPreferencesKey("color_palette")])
        assertEquals(3072, preferences[intPreferencesKey("disk_cache_limit_gb")])
        assertEquals(false, preferences[booleanPreferencesKey("video_resume_enabled")])
        assertEquals(500, preferences[intPreferencesKey("history_max_records")])
        assertEquals("OFF", preferences[stringPreferencesKey("diagnostic_log_level")])
    }

    @Test
    fun videoLibraryThumbnailsEnabledDefaultsToTrue() = runTest {
        val store = createStore("video_library_thumbnail_default.preferences_pb")

        assertTrue(store.settings.first().video.videoLibraryThumbnailsEnabled)
    }

    @Test
    fun videoLibraryThumbnailsEnabledCanBeUpdatedAndReadBack() = runTest {
        val store = createStore("video_library_thumbnail_update.preferences_pb")

        store.updateVideoSettings { video -> video.copy(videoLibraryThumbnailsEnabled = false) }

        assertFalse(store.settings.first().video.videoLibraryThumbnailsEnabled)
    }

    @Test
    fun gridVideoThumbnailsEnabledCanBeUpdatedAndReadBack() = runTest {
        val store = createStore("grid_video_thumbnail_update.preferences_pb")

        store.updateVideoSettings { video -> video.copy(gridVideoThumbnailsEnabled = false) }

        assertFalse(store.settings.first().video.gridVideoThumbnailsEnabled)
    }

    @Test
    fun avifImageSupportDefaultsOffAndCanBeUpdated() = runTest {
        val store = createStore("avif_image_support.preferences_pb")

        assertFalse(store.settings.first().reader.avifImagesEnabled)

        store.updateReaderSettings { reader -> reader.copy(avifImagesEnabled = true) }

        assertTrue(store.settings.first().reader.avifImagesEnabled)
    }

    @Test
    fun pageImageCacheDefaultsOnAndCanBeUpdated() = runTest {
        val store = createStore("page_image_cache_enabled.preferences_pb")

        assertTrue(store.settings.first().storage.pageImageCacheEnabled)

        store.updateStorageSettings { storage -> storage.copy(pageImageCacheEnabled = false) }

        assertFalse(store.settings.first().storage.pageImageCacheEnabled)
    }

    @Test
    fun readerPinchZoomDefaultsOff() = runTest {
        val store = createStore("reader_pinch_zoom_default.preferences_pb")

        assertFalse(store.settings.first().reader.readerPinchZoomEnabled)
    }

    @Test
    fun readerPinchZoomCanBeUpdatedAndReadBack() = runTest {
        val store = createStore("reader_pinch_zoom_update.preferences_pb")

        store.updateReaderSettings { reader -> reader.copy(readerPinchZoomEnabled = true) }

        assertTrue(store.settings.first().reader.readerPinchZoomEnabled)
    }

    @Test
    fun historyPolicyCanBeUpdatedAndIsCoercedToSupportedOptions() = runTest {
        val store = createStore("history_policy.preferences_pb")

        store.updateHistorySettings { history ->
            history.copy(historyRetentionDays = 31, historyMaxRecords = 490)
        }

        val settings = store.settings.first()
        assertEquals(30, settings.history.historyRetentionDays)
        assertEquals(500, settings.history.historyMaxRecords)
    }

    @Test
    fun readerSettingsArePersistedAsOneGroup() = runTest {
        val store = createStore("reader_group.preferences_pb")
        val reader = ReaderSettings(
            readingDirection = ReadingDirection.RIGHT_TO_LEFT,
            avifImagesEnabled = true,
            autoPageEnabled = true,
            autoPageSpeedMillis = 12_000,
            volumeKeysTurnPagesEnabled = true,
            readerPinchZoomEnabled = true,
        )

        store.updateReaderSettings { reader }

        assertEquals(reader, store.settings.first().reader)
    }

    @Test
    fun appearanceSettingsArePersistedAsOneGroup() = runTest {
        val store = createStore("appearance_group.preferences_pb")
        val appearance = AppearanceSettings(
            colorPalette = AppColorPalette.NIGHT,
            screenRotationLockEnabled = true,
            libraryCoversEnabled = false,
        )

        store.updateAppearanceSettings { appearance }

        assertEquals(appearance, store.settings.first().appearance)
    }

    @Test
    fun storageSettingsArePersistedAsOneGroup() = runTest {
        val store = createStore("storage_group.preferences_pb")
        val storage = StorageSettings(
            pageImageCacheEnabled = false,
            diskCacheLimitMb = 2048,
            webDavPrefetchPageCount = 8,
        )

        store.updateStorageSettings { storage }

        assertEquals(storage, store.settings.first().storage)
    }

    @Test
    fun videoSettingsArePersistedAsOneGroup() = runTest {
        val store = createStore("video_group.preferences_pb")
        val video = VideoSettings(
            videoResumeEnabled = false,
            videoSeekOptimizationEnabled = false,
            videoForwardPrefetchMode = VideoForwardPrefetchMode.AGGRESSIVE,
            videoPlayerProxyDebugInfoEnabled = true,
            videoOutputMode = VideoOutputMode.GPU_NEXT,
            gpuApiMode = GpuApiMode.VULKAN,
            anime4kProfile = Anime4KProfile.EXTREME,
            videoDecoderMode = VideoDecoderMode.SOFTWARE,
            mpvProfileMode = MpvProfileMode.HIGH_QUALITY,
            videoControlsAutoHideMillis = 10_000,
            videoPlayerOrientationMode = VideoPlayerOrientationMode.LANDSCAPE,
            videoBackgroundMode = VideoBackgroundMode.BACKGROUND_PLAY,
            gridVideoThumbnailsEnabled = false,
            videoLibraryThumbnailsEnabled = false,
        )

        store.updateVideoSettings { video }

        assertEquals(video, store.settings.first().video)
    }

    @Test
    fun historySettingsArePersistedAsOneGroup() = runTest {
        val store = createStore("history_group.preferences_pb")
        val history = HistorySettings(
            historyRetentionDays = 180,
            historyMaxRecords = 500,
        )

        store.updateHistorySettings { history }

        assertEquals(history, store.settings.first().history)
    }

    @Test
    fun diagnosticsSettingsArePersistedAsOneGroup() = runTest {
        val store = createStore("diagnostics_group.preferences_pb")
        val diagnostics = DiagnosticsSettings(logLevel = DiagnosticLogLevel.OFF)

        store.updateDiagnosticsSettings { diagnostics }

        assertEquals(diagnostics, store.settings.first().diagnostics)
    }

    @Test
    fun updatingOneGroupDoesNotOverwriteOtherGroups() = runTest {
        val store = createStore("group_isolation.preferences_pb")
        store.updateReaderSettings { ReaderSettings(autoPageEnabled = true) }
        store.updateAppearanceSettings { AppearanceSettings(colorPalette = AppColorPalette.SEPIA) }
        store.updateStorageSettings { StorageSettings(diskCacheLimitMb = 2048) }
        store.updateVideoSettings { VideoSettings(videoResumeEnabled = false) }
        store.updateHistorySettings { HistorySettings(historyRetentionDays = 180) }
        store.updateDiagnosticsSettings { DiagnosticsSettings(DiagnosticLogLevel.OFF) }
        val before = store.settings.first()

        store.updateReaderSettings { before.reader.copy(readerPinchZoomEnabled = true) }

        val after = store.settings.first()
        assertTrue(after.reader.readerPinchZoomEnabled)
        assertEquals(before.appearance, after.appearance)
        assertEquals(before.storage, after.storage)
        assertEquals(before.video, after.video)
        assertEquals(before.history, after.history)
        assertEquals(before.diagnostics, after.diagnostics)
    }

    private fun TestScope.createStore(fileName: String): AppSettingsStore {
        val preferencesFile = temporaryFolder.newFile(fileName)
        val dataStore = PreferenceDataStoreFactory.create(
            scope = backgroundScope,
            produceFile = { preferencesFile },
        )
        return AppSettingsStore(dataStore)
    }
}
