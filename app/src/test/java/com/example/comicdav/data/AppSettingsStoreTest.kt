package com.example.comicdav.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

@OptIn(ExperimentalCoroutinesApi::class)
class AppSettingsStoreTest {
    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun emitsConservativeDefaultsWhenSettingsHaveNotBeenSaved() = runTest {
        val store = AppSettingsStore(dataStore("app-settings-empty.preferences_pb"))

        assertEquals(
            AppSettings(
                readingDirection = ReadingDirection.LEFT_TO_RIGHT,
                readerLoggingMode = ReaderLoggingMode.SUMMARY,
                colorPalette = AppColorPalette.DEFAULT,
                autoPageEnabled = false,
                autoPageSpeedMillis = 5_000,
                screenRotationLockEnabled = false,
                volumeKeysTurnPagesEnabled = false,
                diskCacheLimitMb = 1024,
                webDavPrefetchPageCount = 4,
            ),
            store.settings.first(),
        )
        assertTrue(store.settings.first().loggingEnabled)
    }

    @Test
    fun persistsSettingsAcrossStoreInstances() = runTest {
        val dataStore = dataStore("app-settings.preferences_pb")
        val store = AppSettingsStore(dataStore)

        store.updateReadingDirection(ReadingDirection.RIGHT_TO_LEFT)
        store.updateLoggingEnabled(false)
        store.updateColorPalette(AppColorPalette.SEPIA)
        store.updateAutoPageEnabled(true)
        store.updateAutoPageSpeedMillis(7_500)
        store.updateScreenRotationLockEnabled(true)
        store.updateVolumeKeysTurnPagesEnabled(true)
        store.updateDiskCacheLimitMb(500)
        store.updateWebDavPrefetchPageCount(6)

        val restored = AppSettingsStore(dataStore)

        assertEquals(
            AppSettings(
                readingDirection = ReadingDirection.RIGHT_TO_LEFT,
                readerLoggingMode = ReaderLoggingMode.OFF,
                colorPalette = AppColorPalette.SEPIA,
                autoPageEnabled = true,
                autoPageSpeedMillis = 7_500,
                screenRotationLockEnabled = true,
                volumeKeysTurnPagesEnabled = true,
                diskCacheLimitMb = 500,
                webDavPrefetchPageCount = 6,
            ),
            restored.settings.first(),
        )
        assertFalse(restored.settings.first().loggingEnabled)
    }

    @Test
    fun defaultsToSummaryReaderLoggingMode() = runTest {
        val store = AppSettingsStore(dataStore("app-settings-logging-default.preferences_pb"))

        assertEquals(ReaderLoggingMode.SUMMARY, store.settings.first().readerLoggingMode)
        assertTrue(store.settings.first().loggingEnabled)
    }

    @Test
    fun persistsReaderLoggingModeWithStablePreferenceKey() = runTest {
        val dataStore = dataStore("app-settings-logging-mode.preferences_pb")
        val store = AppSettingsStore(dataStore)

        store.updateReaderLoggingMode(ReaderLoggingMode.DETAIL)

        assertEquals(ReaderLoggingMode.DETAIL, AppSettingsStore(dataStore).settings.first().readerLoggingMode)
        assertEquals("DETAIL", dataStore.data.first()[stringPreferencesKey("reader_logging_mode")])
    }

    @Test
    fun migratesOldDisabledLoggingBooleanToOffMode() = runTest {
        val dataStore = dataStore("app-settings-logging-migration.preferences_pb")
        dataStore.edit { preferences ->
            preferences[booleanPreferencesKey("logging_enabled")] = false
        }

        assertEquals(ReaderLoggingMode.OFF, AppSettingsStore(dataStore).settings.first().readerLoggingMode)
    }

    @Test
    fun storesSettingsWithStablePreferenceKeys() = runTest {
        val dataStore = dataStore("app-settings-keys.preferences_pb")
        val store = AppSettingsStore(dataStore)

        store.updateReadingDirection(ReadingDirection.VERTICAL)
        store.updateLoggingEnabled(false)
        store.updateColorPalette(AppColorPalette.NIGHT)
        store.updateAutoPageEnabled(true)
        store.updateAutoPageSpeedMillis(3_000)
        store.updateScreenRotationLockEnabled(true)
        store.updateVolumeKeysTurnPagesEnabled(true)
        store.updateDiskCacheLimitMb(5120)
        store.updateWebDavPrefetchPageCount(8)

        val preferences = dataStore.data.first()

        assertEquals("VERTICAL", preferences[stringPreferencesKey("reading_direction")])
        assertFalse(preferences[booleanPreferencesKey("logging_enabled")]!!)
        assertEquals("OFF", preferences[stringPreferencesKey("reader_logging_mode")])
        assertEquals("NIGHT", preferences[stringPreferencesKey("color_palette")])
        assertTrue(preferences[booleanPreferencesKey("auto_page_enabled")]!!)
        assertEquals(3_000, preferences[intPreferencesKey("auto_page_speed_millis")])
        assertTrue(preferences[booleanPreferencesKey("screen_rotation_lock_enabled")]!!)
        assertTrue(preferences[booleanPreferencesKey("volume_keys_turn_pages_enabled")]!!)
        assertEquals(5120, preferences[intPreferencesKey("disk_cache_limit_gb")])
        assertEquals(8, preferences[intPreferencesKey("webdav_prefetch_page_count")])
    }

    @Test
    fun coercesStoredWebDavPrefetchPageCountToSupportedOption() = runTest {
        val dataStore = dataStore("app-settings-webdav-prefetch-coerce.preferences_pb")
        dataStore.edit { preferences ->
            preferences[intPreferencesKey("webdav_prefetch_page_count")] = 7
        }

        assertEquals(6, AppSettingsStore(dataStore).settings.first().webDavPrefetchPageCount)
    }

    @Test
    fun migratesLegacyDiskCacheLimitGbPreferenceToMb() = runTest {
        val dataStore = dataStore("app-settings-cache-limit-migration.preferences_pb")
        dataStore.edit { preferences ->
            preferences[intPreferencesKey("disk_cache_limit_gb")] = 4
        }

        assertEquals(4096, AppSettingsStore(dataStore).settings.first().diskCacheLimitMb)
    }

    @Test
    fun offersSeveralColorPaletteChoices() {
        assertTrue(AppColorPalette.entries.size >= 4)
    }

    @Test
    fun offersContinuousVerticalReadingDirection() {
        assertTrue(ReadingDirection.entries.contains(ReadingDirection.VERTICAL_CONTINUOUS))
    }

    private fun TestScope.dataStore(fileName: String): DataStore<Preferences> {
        return PreferenceDataStoreFactory.create(
            scope = backgroundScope,
            produceFile = { temp.newFile(fileName) },
        )
    }
}
