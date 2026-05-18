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

enum class ReaderLoggingMode {
    OFF,
    SUMMARY,
    DETAIL,
}

data class AppSettings(
    val readingDirection: ReadingDirection = ReadingDirection.LEFT_TO_RIGHT,
    val readerLoggingMode: ReaderLoggingMode = ReaderLoggingMode.SUMMARY,
    val colorPalette: AppColorPalette = AppColorPalette.DEFAULT,
    val autoPageEnabled: Boolean = false,
    val autoPageSpeedMillis: Int = 5_000,
    val screenRotationLockEnabled: Boolean = false,
    val volumeKeysTurnPagesEnabled: Boolean = false,
    val diskCacheLimitMb: Int = 1024,
    val webDavPrefetchPageCount: Int = 4,
) {
    val loggingEnabled: Boolean
        get() = readerLoggingMode != ReaderLoggingMode.OFF
}

class AppSettingsStore(
    private val dataStore: DataStore<Preferences>,
) {
    val settings: Flow<AppSettings> = dataStore.data.map { preferences ->
        AppSettings(
            readingDirection = preferences[READING_DIRECTION].toEnumOrDefault(ReadingDirection.LEFT_TO_RIGHT),
            readerLoggingMode = preferences[READER_LOGGING_MODE].toEnumOrNull<ReaderLoggingMode>()
                ?: if (preferences[LOGGING_ENABLED] == false) ReaderLoggingMode.OFF else ReaderLoggingMode.SUMMARY,
            colorPalette = preferences[COLOR_PALETTE].toEnumOrDefault(AppColorPalette.DEFAULT),
            autoPageEnabled = preferences[AUTO_PAGE_ENABLED] ?: false,
            autoPageSpeedMillis = preferences[AUTO_PAGE_SPEED_MILLIS] ?: 5_000,
            screenRotationLockEnabled = preferences[SCREEN_ROTATION_LOCK_ENABLED] ?: false,
            volumeKeysTurnPagesEnabled = preferences[VOLUME_KEYS_TURN_PAGES_ENABLED] ?: false,
            diskCacheLimitMb = coerceStoredDiskCacheLimitMb(preferences[DISK_CACHE_LIMIT_MB] ?: 1024),
            webDavPrefetchPageCount = coerceWebDavPrefetchPageCount(preferences[WEB_DAV_PREFETCH_PAGE_COUNT] ?: 4),
        )
    }

    suspend fun updateReadingDirection(readingDirection: ReadingDirection) {
        dataStore.edit { preferences ->
            preferences[READING_DIRECTION] = readingDirection.name
        }
    }

    suspend fun updateLoggingEnabled(enabled: Boolean) {
        updateReaderLoggingMode(if (enabled) ReaderLoggingMode.SUMMARY else ReaderLoggingMode.OFF)
    }

    suspend fun updateReaderLoggingMode(mode: ReaderLoggingMode) {
        dataStore.edit { preferences ->
            preferences[READER_LOGGING_MODE] = mode.name
            preferences[LOGGING_ENABLED] = mode != ReaderLoggingMode.OFF
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

    suspend fun updateDiskCacheLimitMb(limitMb: Int) {
        dataStore.edit { preferences ->
            preferences[DISK_CACHE_LIMIT_MB] = coerceDiskCacheLimitMb(limitMb)
        }
    }

    suspend fun updateWebDavPrefetchPageCount(pageCount: Int) {
        dataStore.edit { preferences ->
            preferences[WEB_DAV_PREFETCH_PAGE_COUNT] = coerceWebDavPrefetchPageCount(pageCount)
        }
    }

    private companion object {
        val READING_DIRECTION = stringPreferencesKey("reading_direction")
        val LOGGING_ENABLED = booleanPreferencesKey("logging_enabled")
        val READER_LOGGING_MODE = stringPreferencesKey("reader_logging_mode")
        val COLOR_PALETTE = stringPreferencesKey("color_palette")
        val AUTO_PAGE_ENABLED = booleanPreferencesKey("auto_page_enabled")
        val AUTO_PAGE_SPEED_MILLIS = intPreferencesKey("auto_page_speed_millis")
        val SCREEN_ROTATION_LOCK_ENABLED = booleanPreferencesKey("screen_rotation_lock_enabled")
        val VOLUME_KEYS_TURN_PAGES_ENABLED = booleanPreferencesKey("volume_keys_turn_pages_enabled")
        val DISK_CACHE_LIMIT_MB = intPreferencesKey("disk_cache_limit_gb")
        val WEB_DAV_PREFETCH_PAGE_COUNT = intPreferencesKey("webdav_prefetch_page_count")
    }
}

private val SupportedDiskCacheLimitMb = listOf(0, 500, 1024, 2048, 3072, 4096, 5120)
private val SupportedWebDavPrefetchPageCounts = listOf(2, 4, 6, 8)

private fun coerceStoredDiskCacheLimitMb(limitMb: Int): Int =
    if (limitMb in 1..5) {
        limitMb * 1024
    } else {
        coerceDiskCacheLimitMb(limitMb)
    }

private fun coerceDiskCacheLimitMb(limitMb: Int): Int =
    SupportedDiskCacheLimitMb.minBy { kotlin.math.abs(it - limitMb) }

private fun coerceWebDavPrefetchPageCount(pageCount: Int): Int =
    SupportedWebDavPrefetchPageCounts.minBy { kotlin.math.abs(it - pageCount) }

private inline fun <reified T : Enum<T>> String?.toEnumOrDefault(default: T): T {
    return this?.let { value -> runCatching { enumValueOf<T>(value) }.getOrNull() } ?: default
}

private inline fun <reified T : Enum<T>> String?.toEnumOrNull(): T? {
    return this?.let { value -> runCatching { enumValueOf<T>(value) }.getOrNull() }
}
