package com.example.comicdav.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

enum class ReadingDirection {
    LEFT_TO_RIGHT,
    RIGHT_TO_LEFT,
    VERTICAL,
    VERTICAL_CONTINUOUS,
}

enum class AppColorPalette {
    DEFAULT,
    SEPIA,
    NIGHT,
    HIGH_CONTRAST,
}

data class AppSettings(
    val readingDirection: ReadingDirection = ReadingDirection.LEFT_TO_RIGHT,
    val loggingEnabled: Boolean = true,
    val colorPalette: AppColorPalette = AppColorPalette.DEFAULT,
    val autoPageEnabled: Boolean = false,
    val autoPageSpeedMillis: Int = 5_000,
    val screenRotationLockEnabled: Boolean = false,
    val volumeKeysTurnPagesEnabled: Boolean = false,
)

class AppSettingsStore(
    private val dataStore: DataStore<Preferences>,
) {
    val settings: Flow<AppSettings> = dataStore.data.map { preferences ->
        AppSettings(
            readingDirection = preferences[READING_DIRECTION].toEnumOrDefault(ReadingDirection.LEFT_TO_RIGHT),
            loggingEnabled = preferences[LOGGING_ENABLED] ?: true,
            colorPalette = preferences[COLOR_PALETTE].toEnumOrDefault(AppColorPalette.DEFAULT),
            autoPageEnabled = preferences[AUTO_PAGE_ENABLED] ?: false,
            autoPageSpeedMillis = preferences[AUTO_PAGE_SPEED_MILLIS] ?: 5_000,
            screenRotationLockEnabled = preferences[SCREEN_ROTATION_LOCK_ENABLED] ?: false,
            volumeKeysTurnPagesEnabled = preferences[VOLUME_KEYS_TURN_PAGES_ENABLED] ?: false,
        )
    }

    suspend fun updateReadingDirection(readingDirection: ReadingDirection) {
        dataStore.edit { preferences ->
            preferences[READING_DIRECTION] = readingDirection.name
        }
    }

    suspend fun updateLoggingEnabled(enabled: Boolean) {
        dataStore.edit { preferences ->
            preferences[LOGGING_ENABLED] = enabled
        }
    }

    suspend fun updateColorPalette(colorPalette: AppColorPalette) {
        dataStore.edit { preferences ->
            preferences[COLOR_PALETTE] = colorPalette.name
        }
    }

    suspend fun updateAutoPageEnabled(enabled: Boolean) {
        dataStore.edit { preferences ->
            preferences[AUTO_PAGE_ENABLED] = enabled
        }
    }

    suspend fun updateAutoPageSpeedMillis(speedMillis: Int) {
        dataStore.edit { preferences ->
            preferences[AUTO_PAGE_SPEED_MILLIS] = speedMillis
        }
    }

    suspend fun updateScreenRotationLockEnabled(enabled: Boolean) {
        dataStore.edit { preferences ->
            preferences[SCREEN_ROTATION_LOCK_ENABLED] = enabled
        }
    }

    suspend fun updateVolumeKeysTurnPagesEnabled(enabled: Boolean) {
        dataStore.edit { preferences ->
            preferences[VOLUME_KEYS_TURN_PAGES_ENABLED] = enabled
        }
    }

    private companion object {
        val READING_DIRECTION = stringPreferencesKey("reading_direction")
        val LOGGING_ENABLED = booleanPreferencesKey("logging_enabled")
        val COLOR_PALETTE = stringPreferencesKey("color_palette")
        val AUTO_PAGE_ENABLED = booleanPreferencesKey("auto_page_enabled")
        val AUTO_PAGE_SPEED_MILLIS = intPreferencesKey("auto_page_speed_millis")
        val SCREEN_ROTATION_LOCK_ENABLED = booleanPreferencesKey("screen_rotation_lock_enabled")
        val VOLUME_KEYS_TURN_PAGES_ENABLED = booleanPreferencesKey("volume_keys_turn_pages_enabled")
    }
}

private inline fun <reified T : Enum<T>> String?.toEnumOrDefault(default: T): T {
    return this?.let { value -> runCatching { enumValueOf<T>(value) }.getOrNull() } ?: default
}
