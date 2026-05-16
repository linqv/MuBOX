package com.example.comicdav.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
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
                loggingEnabled = true,
                colorPalette = AppColorPalette.DEFAULT,
                autoPageEnabled = false,
                autoPageSpeedMillis = 5_000,
                screenRotationLockEnabled = false,
                volumeKeysTurnPagesEnabled = false,
            ),
            store.settings.first(),
        )
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

        val restored = AppSettingsStore(dataStore)

        assertEquals(
            AppSettings(
                readingDirection = ReadingDirection.RIGHT_TO_LEFT,
                loggingEnabled = false,
                colorPalette = AppColorPalette.SEPIA,
                autoPageEnabled = true,
                autoPageSpeedMillis = 7_500,
                screenRotationLockEnabled = true,
                volumeKeysTurnPagesEnabled = true,
            ),
            restored.settings.first(),
        )
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

        val preferences = dataStore.data.first()

        assertEquals("VERTICAL", preferences[stringPreferencesKey("reading_direction")])
        assertFalse(preferences[booleanPreferencesKey("logging_enabled")]!!)
        assertEquals("NIGHT", preferences[stringPreferencesKey("color_palette")])
        assertTrue(preferences[booleanPreferencesKey("auto_page_enabled")]!!)
        assertEquals(3_000, preferences[intPreferencesKey("auto_page_speed_millis")])
        assertTrue(preferences[booleanPreferencesKey("screen_rotation_lock_enabled")]!!)
        assertTrue(preferences[booleanPreferencesKey("volume_keys_turn_pages_enabled")]!!)
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
