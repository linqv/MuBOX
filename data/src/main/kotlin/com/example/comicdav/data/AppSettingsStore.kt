package com.example.comicdav.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.example.comicdav.core.model.settings.Anime4KMode
import com.example.comicdav.core.model.settings.Anime4KQuality
import com.example.comicdav.core.model.settings.AppColorPalette
import com.example.comicdav.core.model.settings.AppSettings
import com.example.comicdav.core.model.settings.GpuApiMode
import com.example.comicdav.core.model.settings.MpvProfileMode
import com.example.comicdav.core.model.settings.ReaderLoggingMode
import com.example.comicdav.core.model.settings.ReadingDirection
import com.example.comicdav.core.model.settings.VideoBackgroundMode
import com.example.comicdav.core.model.settings.VideoDecoderMode
import com.example.comicdav.core.model.settings.VideoForwardPrefetchMode
import com.example.comicdav.core.model.settings.VideoOutputMode
import com.example.comicdav.core.model.settings.VideoPlayerOrientationMode
import com.example.comicdav.core.model.settings.VideoProxyDiagnosticsMode
import com.example.comicdav.core.model.settings.playerControlAutoHideOptionsMillis
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

fun AppColorPalette.displayLabel(): String = when (this) {
    AppColorPalette.DEFAULT -> "Adwaita 深色（默认）"
    AppColorPalette.ADWAITA_LIGHT -> "Adwaita 浅色"
    AppColorPalette.ADWAITA_BLUE_GRAY -> "Adwaita 蓝灰"
    AppColorPalette.ADWAITA_PURPLE -> "Adwaita 紫色"
    AppColorPalette.CINEMA_DARK -> "影院深色（旧）"
    AppColorPalette.SEPIA -> "纸张护眼"
    AppColorPalette.NIGHT -> "夜间深色"
    AppColorPalette.HIGH_CONTRAST -> "高对比"
}

class AppSettingsStore(
    private val dataStore: DataStore<Preferences>,
) {
    val settings: Flow<AppSettings> = dataStore.data.map { preferences ->
        val storedDiskCacheLimitMb = preferences[DISK_CACHE_LIMIT_MB]
        AppSettings(
            readingDirection = preferences[READING_DIRECTION].toEnumOrDefault(ReadingDirection.LEFT_TO_RIGHT),
            readerLoggingMode = preferences[READER_LOGGING_MODE].toEnumOrNull<ReaderLoggingMode>()
                ?: if (preferences[LOGGING_ENABLED] == false) ReaderLoggingMode.OFF else ReaderLoggingMode.SUMMARY,
            colorPalette = preferences[COLOR_PALETTE].toEnumOrDefault(AppColorPalette.DEFAULT),
            avifImagesEnabled = preferences[AVIF_IMAGES_ENABLED] ?: false,
            autoPageEnabled = preferences[AUTO_PAGE_ENABLED] ?: false,
            autoPageSpeedMillis = preferences[AUTO_PAGE_SPEED_MILLIS] ?: 5_000,
            screenRotationLockEnabled = preferences[SCREEN_ROTATION_LOCK_ENABLED] ?: false,
            volumeKeysTurnPagesEnabled = preferences[VOLUME_KEYS_TURN_PAGES_ENABLED] ?: false,
            readerPinchZoomEnabled = preferences[READER_PINCH_ZOOM_ENABLED] ?: false,
            pageImageCacheEnabled = preferences[PAGE_IMAGE_CACHE_ENABLED] ?: (storedDiskCacheLimitMb != 0),
            diskCacheLimitMb = coerceStoredDiskCacheLimitMb(storedDiskCacheLimitMb ?: 1024),
            webDavPrefetchPageCount = coerceWebDavPrefetchPageCount(preferences[WEB_DAV_PREFETCH_PAGE_COUNT] ?: 4),
            libraryCoversEnabled = preferences[LIBRARY_COVERS_ENABLED] ?: true,
            videoResumeEnabled = preferences[VIDEO_RESUME_ENABLED] ?: true,
            videoSeekOptimizationEnabled = preferences[VIDEO_SEEK_OPTIMIZATION_ENABLED] ?: true,
            videoForwardPrefetchMode = preferences[VIDEO_FORWARD_PREFETCH_MODE]
                .toEnumOrDefault(VideoForwardPrefetchMode.STANDARD),
            videoProxyDiagnosticsMode = preferences[VIDEO_PROXY_DIAGNOSTICS_MODE]
                .toEnumOrDefault(VideoProxyDiagnosticsMode.OFF),
            videoPlayerProxyDebugInfoEnabled = preferences[VIDEO_PLAYER_PROXY_DEBUG_INFO_ENABLED] ?: false,
            videoOutputMode = preferences[VIDEO_OUTPUT_MODE].toEnumOrDefault(VideoOutputMode.AUTO),
            gpuApiMode = preferences[GPU_API_MODE].toEnumOrDefault(GpuApiMode.AUTO),
            anime4kEnabled = preferences[ANIME4K_ENABLED] ?: false,
            anime4kMode = preferences[ANIME4K_MODE].toEnumOrDefault(Anime4KMode.A),
            anime4kQuality = preferences[ANIME4K_QUALITY].toEnumOrDefault(Anime4KQuality.FAST),
            videoDecoderMode = preferences[VIDEO_DECODER_MODE].toEnumOrDefault(VideoDecoderMode.AUTO),
            mpvProfileMode = preferences[MPV_PROFILE_MODE].toEnumOrDefault(MpvProfileMode.FAST),
            videoControlsAutoHideMillis = coerceVideoControlsAutoHideMillis(
                preferences[VIDEO_CONTROLS_AUTO_HIDE_MILLIS] ?: 5_000,
            ),
            videoPlayerOrientationMode = preferences[VIDEO_PLAYER_ORIENTATION_MODE]
                .toEnumOrDefault(VideoPlayerOrientationMode.VIDEO),
            videoBackgroundMode = preferences[VIDEO_BACKGROUND_MODE]
                .toEnumOrDefault(VideoBackgroundMode.NONE),
            videoLibraryThumbnailsEnabled = preferences[VIDEO_LIBRARY_THUMBNAILS_ENABLED] ?: true,
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

    suspend fun updateAvifImagesEnabled(enabled: Boolean) {
        dataStore.edit { preferences ->
            preferences[AVIF_IMAGES_ENABLED] = enabled
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

    suspend fun updateReaderPinchZoomEnabled(enabled: Boolean) {
        dataStore.edit { preferences ->
            preferences[READER_PINCH_ZOOM_ENABLED] = enabled
        }
    }

    suspend fun updatePageImageCacheEnabled(enabled: Boolean) {
        dataStore.edit { preferences ->
            preferences[PAGE_IMAGE_CACHE_ENABLED] = enabled
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

    suspend fun updateLibraryCoversEnabled(enabled: Boolean) {
        dataStore.edit { preferences ->
            preferences[LIBRARY_COVERS_ENABLED] = enabled
        }
    }

    suspend fun updateVideoResumeEnabled(enabled: Boolean) {
        dataStore.edit { preferences ->
            preferences[VIDEO_RESUME_ENABLED] = enabled
        }
    }

    suspend fun updateVideoSeekOptimizationEnabled(enabled: Boolean) {
        dataStore.edit { preferences ->
            preferences[VIDEO_SEEK_OPTIMIZATION_ENABLED] = enabled
        }
    }

    suspend fun updateVideoForwardPrefetchMode(mode: VideoForwardPrefetchMode) {
        dataStore.edit { preferences ->
            preferences[VIDEO_FORWARD_PREFETCH_MODE] = mode.name
        }
    }

    suspend fun updateVideoProxyDiagnosticsMode(mode: VideoProxyDiagnosticsMode) {
        dataStore.edit { preferences ->
            preferences[VIDEO_PROXY_DIAGNOSTICS_MODE] = mode.name
        }
    }

    suspend fun updateVideoPlayerProxyDebugInfoEnabled(enabled: Boolean) {
        dataStore.edit { preferences ->
            preferences[VIDEO_PLAYER_PROXY_DEBUG_INFO_ENABLED] = enabled
        }
    }

    suspend fun updateVideoOutputMode(mode: VideoOutputMode) {
        dataStore.edit { preferences ->
            preferences[VIDEO_OUTPUT_MODE] = mode.name
        }
    }

    suspend fun updateGpuApiMode(mode: GpuApiMode) {
        dataStore.edit { preferences ->
            preferences[GPU_API_MODE] = mode.name
        }
    }

    suspend fun updateAnime4KEnabled(enabled: Boolean) {
        dataStore.edit { preferences ->
            preferences[ANIME4K_ENABLED] = enabled
        }
    }

    suspend fun updateAnime4KMode(mode: Anime4KMode) {
        dataStore.edit { preferences ->
            preferences[ANIME4K_MODE] = mode.name
        }
    }

    suspend fun updateAnime4KQuality(quality: Anime4KQuality) {
        dataStore.edit { preferences ->
            preferences[ANIME4K_QUALITY] = quality.name
        }
    }

    suspend fun updateVideoDecoderMode(mode: VideoDecoderMode) {
        dataStore.edit { preferences ->
            preferences[VIDEO_DECODER_MODE] = mode.name
        }
    }

    suspend fun updateMpvProfileMode(mode: MpvProfileMode) {
        dataStore.edit { preferences ->
            preferences[MPV_PROFILE_MODE] = mode.name
        }
    }

    suspend fun updateVideoControlsAutoHideMillis(millis: Int) {
        dataStore.edit { preferences ->
            preferences[VIDEO_CONTROLS_AUTO_HIDE_MILLIS] = coerceVideoControlsAutoHideMillis(millis)
        }
    }

    suspend fun updateVideoPlayerOrientationMode(mode: VideoPlayerOrientationMode) {
        dataStore.edit { preferences ->
            preferences[VIDEO_PLAYER_ORIENTATION_MODE] = mode.name
        }
    }

    suspend fun updateVideoLibraryThumbnailsEnabled(enabled: Boolean) {
        dataStore.edit { preferences ->
            preferences[VIDEO_LIBRARY_THUMBNAILS_ENABLED] = enabled
        }
    }

    suspend fun updateVideoBackgroundMode(mode: VideoBackgroundMode) {
        dataStore.edit { preferences ->
            preferences[VIDEO_BACKGROUND_MODE] = mode.name
        }
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
        val ANIME4K_ENABLED = booleanPreferencesKey("anime4k_enabled")
        val ANIME4K_MODE = stringPreferencesKey("anime4k_mode")
        val ANIME4K_QUALITY = stringPreferencesKey("anime4k_quality")
        val VIDEO_DECODER_MODE = stringPreferencesKey("video_decoder_mode")
        val MPV_PROFILE_MODE = stringPreferencesKey("mpv_profile_mode")
        val VIDEO_CONTROLS_AUTO_HIDE_MILLIS = intPreferencesKey("video_controls_auto_hide_millis")
        val VIDEO_PLAYER_ORIENTATION_MODE = stringPreferencesKey("video_player_orientation_mode")
        val VIDEO_BACKGROUND_MODE = stringPreferencesKey("video_background_mode")
        val VIDEO_LIBRARY_THUMBNAILS_ENABLED = booleanPreferencesKey("video_library_thumbnails_enabled")
    }
}

private val SupportedDiskCacheLimitMb = listOf(500, 1024, 2048, 3072, 4096, 5120)
private val SupportedWebDavPrefetchPageCounts = listOf(2, 4, 6, 8, 10, 12)

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

private inline fun <reified T : Enum<T>> String?.toEnumOrDefault(default: T): T {
    return this?.let { value -> runCatching { enumValueOf<T>(value) }.getOrNull() } ?: default
}

private inline fun <reified T : Enum<T>> String?.toEnumOrNull(): T? {
    return this?.let { value -> runCatching { enumValueOf<T>(value) }.getOrNull() }
}
