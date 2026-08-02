package com.example.comicdav.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.example.comicdav.core.model.settings.Anime4KProfile
import com.example.comicdav.core.model.settings.AppColorPalette
import com.example.comicdav.core.model.settings.AppSettings
import com.example.comicdav.core.model.settings.AppearanceSettings
import com.example.comicdav.core.model.settings.GpuApiMode
import com.example.comicdav.core.model.settings.HistorySettings
import com.example.comicdav.core.model.settings.MpvProfileMode
import com.example.comicdav.core.model.settings.ReaderLoggingMode
import com.example.comicdav.core.model.settings.ReaderSettings
import com.example.comicdav.core.model.settings.ReadingDirection
import com.example.comicdav.core.model.settings.StorageSettings
import com.example.comicdav.core.model.settings.VideoBackgroundMode
import com.example.comicdav.core.model.settings.VideoDecoderMode
import com.example.comicdav.core.model.settings.VideoForwardPrefetchMode
import com.example.comicdav.core.model.settings.VideoOutputMode
import com.example.comicdav.core.model.settings.VideoPlayerOrientationMode
import com.example.comicdav.core.model.settings.VideoProxyDiagnosticsMode
import com.example.comicdav.core.model.settings.VideoSettings
import com.example.comicdav.core.model.settings.anime4KProfileFromLegacy
import com.example.comicdav.core.model.settings.playerControlAutoHideOptionsMillis
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class AppSettingsStore(
    private val dataStore: DataStore<Preferences>,
) {
    val settings: Flow<AppSettings> = dataStore.data.map(::appSettingsFrom)

    private fun appSettingsFrom(preferences: Preferences): AppSettings =
        AppSettings(
            reader = readerSettingsFrom(preferences),
            appearance = appearanceSettingsFrom(preferences),
            storage = storageSettingsFrom(preferences),
            video = videoSettingsFrom(preferences),
            history = historySettingsFrom(preferences),
        )

    private fun readerSettingsFrom(preferences: Preferences): ReaderSettings =
        ReaderSettings(
            readingDirection = preferences[READING_DIRECTION]
                .toEnumOrDefault(ReadingDirection.LEFT_TO_RIGHT),
            readerLoggingMode = preferences[READER_LOGGING_MODE].toEnumOrNull<ReaderLoggingMode>()
                ?: if (preferences[LOGGING_ENABLED] == false) {
                    ReaderLoggingMode.OFF
                } else {
                    ReaderLoggingMode.SUMMARY
                },
            avifImagesEnabled = preferences[AVIF_IMAGES_ENABLED] ?: false,
            autoPageEnabled = preferences[AUTO_PAGE_ENABLED] ?: false,
            autoPageSpeedMillis = preferences[AUTO_PAGE_SPEED_MILLIS] ?: 5_000,
            volumeKeysTurnPagesEnabled = preferences[VOLUME_KEYS_TURN_PAGES_ENABLED] ?: false,
            readerPinchZoomEnabled = preferences[READER_PINCH_ZOOM_ENABLED] ?: false,
        )

    private fun appearanceSettingsFrom(preferences: Preferences): AppearanceSettings =
        AppearanceSettings(
            colorPalette = preferences[COLOR_PALETTE].toEnumOrDefault(AppColorPalette.DEFAULT),
            screenRotationLockEnabled = preferences[SCREEN_ROTATION_LOCK_ENABLED] ?: false,
            libraryCoversEnabled = preferences[LIBRARY_COVERS_ENABLED] ?: true,
        )

    private fun storageSettingsFrom(preferences: Preferences): StorageSettings {
        val storedDiskCacheLimitMb = preferences[DISK_CACHE_LIMIT_MB]
        return StorageSettings(
            pageImageCacheEnabled = preferences[PAGE_IMAGE_CACHE_ENABLED] ?: (storedDiskCacheLimitMb != 0),
            diskCacheLimitMb = coerceStoredDiskCacheLimitMb(storedDiskCacheLimitMb ?: 1024),
            webDavPrefetchPageCount = coerceWebDavPrefetchPageCount(
                preferences[WEB_DAV_PREFETCH_PAGE_COUNT] ?: 4,
            ),
        )
    }

    private fun videoSettingsFrom(preferences: Preferences): VideoSettings =
        VideoSettings(
            videoResumeEnabled = preferences[VIDEO_RESUME_ENABLED] ?: true,
            videoSeekOptimizationEnabled = preferences[VIDEO_SEEK_OPTIMIZATION_ENABLED] ?: true,
            videoForwardPrefetchMode = preferences[VIDEO_FORWARD_PREFETCH_MODE]
                .toEnumOrDefault(VideoForwardPrefetchMode.STANDARD),
            videoProxyDiagnosticsMode = preferences[VIDEO_PROXY_DIAGNOSTICS_MODE]
                .toEnumOrDefault(VideoProxyDiagnosticsMode.OFF),
            videoPlayerProxyDebugInfoEnabled = preferences[VIDEO_PLAYER_PROXY_DEBUG_INFO_ENABLED] ?: false,
            videoOutputMode = preferences[VIDEO_OUTPUT_MODE].toEnumOrDefault(VideoOutputMode.AUTO),
            gpuApiMode = preferences[GPU_API_MODE].toEnumOrDefault(GpuApiMode.AUTO),
            anime4kProfile = preferences[ANIME4K_PROFILE].toEnumOrNull<Anime4KProfile>()
                ?: anime4KProfileFromLegacy(
                    enabled = preferences[ANIME4K_ENABLED] ?: false,
                    mode = preferences[ANIME4K_MODE],
                    quality = preferences[ANIME4K_QUALITY],
                ),
            videoDecoderMode = preferences[VIDEO_DECODER_MODE].toEnumOrDefault(VideoDecoderMode.AUTO),
            mpvProfileMode = preferences[MPV_PROFILE_MODE].toEnumOrDefault(MpvProfileMode.FAST),
            videoControlsAutoHideMillis = coerceVideoControlsAutoHideMillis(
                preferences[VIDEO_CONTROLS_AUTO_HIDE_MILLIS] ?: 5_000,
            ),
            videoPlayerOrientationMode = preferences[VIDEO_PLAYER_ORIENTATION_MODE]
                .toEnumOrDefault(VideoPlayerOrientationMode.VIDEO),
            videoBackgroundMode = preferences[VIDEO_BACKGROUND_MODE]
                .toEnumOrDefault(VideoBackgroundMode.NONE),
            gridVideoThumbnailsEnabled = preferences[GRID_VIDEO_THUMBNAILS_ENABLED] ?: true,
            videoLibraryThumbnailsEnabled = preferences[VIDEO_LIBRARY_THUMBNAILS_ENABLED] ?: true,
        )

    private fun historySettingsFrom(preferences: Preferences): HistorySettings =
        HistorySettings(
            historyRetentionDays = coerceHistoryRetentionDays(preferences[HISTORY_RETENTION_DAYS] ?: 90),
            historyMaxRecords = coerceHistoryMaxRecords(preferences[HISTORY_MAX_RECORDS] ?: 200),
        )

    suspend fun updateReaderSettings(transform: (ReaderSettings) -> ReaderSettings) {
        dataStore.edit { preferences ->
            preferences.writeReaderSettings(transform(readerSettingsFrom(preferences)))
        }
    }

    suspend fun updateAppearanceSettings(transform: (AppearanceSettings) -> AppearanceSettings) {
        dataStore.edit { preferences ->
            preferences.writeAppearanceSettings(transform(appearanceSettingsFrom(preferences)))
        }
    }

    suspend fun updateStorageSettings(transform: (StorageSettings) -> StorageSettings) {
        dataStore.edit { preferences ->
            preferences.writeStorageSettings(transform(storageSettingsFrom(preferences)))
        }
    }

    suspend fun updateVideoSettings(transform: (VideoSettings) -> VideoSettings) {
        dataStore.edit { preferences ->
            preferences.writeVideoSettings(transform(videoSettingsFrom(preferences)))
        }
    }

    suspend fun updateHistorySettings(transform: (HistorySettings) -> HistorySettings) {
        dataStore.edit { preferences ->
            preferences.writeHistorySettings(transform(historySettingsFrom(preferences)))
        }
    }

    private fun MutablePreferences.writeReaderSettings(settings: ReaderSettings) {
        this[READING_DIRECTION] = settings.readingDirection.name
        this[READER_LOGGING_MODE] = settings.readerLoggingMode.name
        this[LOGGING_ENABLED] = settings.readerLoggingMode != ReaderLoggingMode.OFF
        this[AVIF_IMAGES_ENABLED] = settings.avifImagesEnabled
        this[AUTO_PAGE_ENABLED] = settings.autoPageEnabled
        this[AUTO_PAGE_SPEED_MILLIS] = settings.autoPageSpeedMillis
        this[VOLUME_KEYS_TURN_PAGES_ENABLED] = settings.volumeKeysTurnPagesEnabled
        this[READER_PINCH_ZOOM_ENABLED] = settings.readerPinchZoomEnabled
    }

    private fun MutablePreferences.writeAppearanceSettings(settings: AppearanceSettings) {
        this[COLOR_PALETTE] = settings.colorPalette.name
        this[SCREEN_ROTATION_LOCK_ENABLED] = settings.screenRotationLockEnabled
        this[LIBRARY_COVERS_ENABLED] = settings.libraryCoversEnabled
    }

    private fun MutablePreferences.writeStorageSettings(settings: StorageSettings) {
        this[PAGE_IMAGE_CACHE_ENABLED] = settings.pageImageCacheEnabled
        this[DISK_CACHE_LIMIT_MB] = coerceDiskCacheLimitMb(settings.diskCacheLimitMb)
        this[WEB_DAV_PREFETCH_PAGE_COUNT] = coerceWebDavPrefetchPageCount(
            settings.webDavPrefetchPageCount,
        )
    }

    private fun MutablePreferences.writeVideoSettings(settings: VideoSettings) {
        this[VIDEO_RESUME_ENABLED] = settings.videoResumeEnabled
        this[VIDEO_SEEK_OPTIMIZATION_ENABLED] = settings.videoSeekOptimizationEnabled
        this[VIDEO_FORWARD_PREFETCH_MODE] = settings.videoForwardPrefetchMode.name
        this[VIDEO_PROXY_DIAGNOSTICS_MODE] = settings.videoProxyDiagnosticsMode.name
        this[VIDEO_PLAYER_PROXY_DEBUG_INFO_ENABLED] = settings.videoPlayerProxyDebugInfoEnabled
        this[VIDEO_OUTPUT_MODE] = settings.videoOutputMode.name
        this[GPU_API_MODE] = settings.gpuApiMode.name
        this[ANIME4K_PROFILE] = settings.anime4kProfile.name
        this[VIDEO_DECODER_MODE] = settings.videoDecoderMode.name
        this[MPV_PROFILE_MODE] = settings.mpvProfileMode.name
        this[VIDEO_CONTROLS_AUTO_HIDE_MILLIS] = coerceVideoControlsAutoHideMillis(
            settings.videoControlsAutoHideMillis,
        )
        this[VIDEO_PLAYER_ORIENTATION_MODE] = settings.videoPlayerOrientationMode.name
        this[VIDEO_BACKGROUND_MODE] = settings.videoBackgroundMode.name
        this[GRID_VIDEO_THUMBNAILS_ENABLED] = settings.gridVideoThumbnailsEnabled
        this[VIDEO_LIBRARY_THUMBNAILS_ENABLED] = settings.videoLibraryThumbnailsEnabled
    }

    private fun MutablePreferences.writeHistorySettings(settings: HistorySettings) {
        this[HISTORY_RETENTION_DAYS] = coerceHistoryRetentionDays(settings.historyRetentionDays)
        this[HISTORY_MAX_RECORDS] = coerceHistoryMaxRecords(settings.historyMaxRecords)
    }

    private companion object {
        val READING_DIRECTION = stringPreferencesKey("reading_direction")
        val LOGGING_ENABLED = booleanPreferencesKey("logging_enabled")
        val READER_LOGGING_MODE = stringPreferencesKey("reader_logging_mode")
        val COLOR_PALETTE = stringPreferencesKey("color_palette")
        val AVIF_IMAGES_ENABLED = booleanPreferencesKey("avif_images_enabled")
        val AUTO_PAGE_ENABLED = booleanPreferencesKey("auto_page_enabled")
        val AUTO_PAGE_SPEED_MILLIS = intPreferencesKey("auto_page_speed_millis")
        val SCREEN_ROTATION_LOCK_ENABLED = booleanPreferencesKey("screen_rotation_lock_enabled")
        val VOLUME_KEYS_TURN_PAGES_ENABLED = booleanPreferencesKey("volume_keys_turn_pages_enabled")
        val READER_PINCH_ZOOM_ENABLED = booleanPreferencesKey("reader_pinch_zoom_enabled")
        val PAGE_IMAGE_CACHE_ENABLED = booleanPreferencesKey("page_image_cache_enabled")
        val DISK_CACHE_LIMIT_MB = intPreferencesKey("disk_cache_limit_gb")
        val WEB_DAV_PREFETCH_PAGE_COUNT = intPreferencesKey("webdav_prefetch_page_count")
        val LIBRARY_COVERS_ENABLED = booleanPreferencesKey("library_covers_enabled")
        val VIDEO_RESUME_ENABLED = booleanPreferencesKey("video_resume_enabled")
        val VIDEO_SEEK_OPTIMIZATION_ENABLED = booleanPreferencesKey("video_seek_optimization_enabled")
        val VIDEO_FORWARD_PREFETCH_MODE = stringPreferencesKey("video_forward_prefetch_mode")
        val VIDEO_PROXY_DIAGNOSTICS_MODE = stringPreferencesKey("video_proxy_diagnostics_mode")
        val VIDEO_PLAYER_PROXY_DEBUG_INFO_ENABLED = booleanPreferencesKey("video_player_proxy_debug_info_enabled")
        val VIDEO_OUTPUT_MODE = stringPreferencesKey("video_output_mode")
        val GPU_API_MODE = stringPreferencesKey("gpu_api_mode")
        val ANIME4K_PROFILE = stringPreferencesKey("anime4k_profile")
        // Read-only legacy keys kept so existing installations migrate without resetting.
        val ANIME4K_ENABLED = booleanPreferencesKey("anime4k_enabled")
        val ANIME4K_MODE = stringPreferencesKey("anime4k_mode")
        val ANIME4K_QUALITY = stringPreferencesKey("anime4k_quality")
        val VIDEO_DECODER_MODE = stringPreferencesKey("video_decoder_mode")
        val MPV_PROFILE_MODE = stringPreferencesKey("mpv_profile_mode")
        val VIDEO_CONTROLS_AUTO_HIDE_MILLIS = intPreferencesKey("video_controls_auto_hide_millis")
        val VIDEO_PLAYER_ORIENTATION_MODE = stringPreferencesKey("video_player_orientation_mode")
        val VIDEO_BACKGROUND_MODE = stringPreferencesKey("video_background_mode")
        val GRID_VIDEO_THUMBNAILS_ENABLED = booleanPreferencesKey("grid_video_thumbnails_enabled")
        val VIDEO_LIBRARY_THUMBNAILS_ENABLED = booleanPreferencesKey("video_library_thumbnails_enabled")
        val HISTORY_RETENTION_DAYS = intPreferencesKey("history_retention_days")
        val HISTORY_MAX_RECORDS = intPreferencesKey("history_max_records")
    }
}

private val SupportedDiskCacheLimitMb = listOf(500, 1024, 2048, 3072, 4096, 5120)
private val SupportedWebDavPrefetchPageCounts = listOf(2, 4, 6, 8, 10, 12)
private val SupportedHistoryRetentionDays = listOf(0, 7, 30, 90, 180, 365)
private val SupportedHistoryMaxRecords = listOf(50, 100, 200, 500, 1_000)

private fun coerceStoredDiskCacheLimitMb(limitMb: Int): Int =
    when {
        limitMb == 0 -> 1024
        limitMb in 1..5 -> limitMb * 1024
        else -> coerceDiskCacheLimitMb(limitMb)
    }

private fun coerceDiskCacheLimitMb(limitMb: Int): Int =
    SupportedDiskCacheLimitMb.minBy { kotlin.math.abs(it - limitMb) }

private fun coerceWebDavPrefetchPageCount(pageCount: Int): Int =
    SupportedWebDavPrefetchPageCounts.minBy { kotlin.math.abs(it - pageCount) }

private fun coerceVideoControlsAutoHideMillis(millis: Int): Int =
    playerControlAutoHideOptionsMillis().minBy { kotlin.math.abs(it - millis) }

private fun coerceHistoryRetentionDays(days: Int): Int =
    SupportedHistoryRetentionDays.minBy { kotlin.math.abs(it - days) }

private fun coerceHistoryMaxRecords(maxRecords: Int): Int =
    SupportedHistoryMaxRecords.minBy { kotlin.math.abs(it - maxRecords) }

private inline fun <reified T : Enum<T>> String?.toEnumOrDefault(default: T): T {
    return this?.let { value -> runCatching { enumValueOf<T>(value) }.getOrNull() } ?: default
}

private inline fun <reified T : Enum<T>> String?.toEnumOrNull(): T? {
    return this?.let { value -> runCatching { enumValueOf<T>(value) }.getOrNull() }
}
