package com.example.comicdav.data

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.example.comicdav.core.model.settings.Anime4KMode
import com.example.comicdav.core.model.settings.Anime4KQuality
import com.example.comicdav.core.model.settings.MpvProfileMode
import com.example.comicdav.core.model.settings.VideoForwardPrefetchMode
import com.example.comicdav.core.model.settings.VideoProxyDiagnosticsMode
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
    fun defaultsEnableVideoSeekOptimizationWithStandardPrefetchAndDiagnosticsOff() = runTest {
        val store = createStore("defaults.preferences_pb")

        val settings = store.settings.first()

        assertTrue(settings.videoSeekOptimizationEnabled)
        assertEquals(VideoForwardPrefetchMode.STANDARD, settings.videoForwardPrefetchMode)
        assertEquals(VideoProxyDiagnosticsMode.OFF, settings.videoProxyDiagnosticsMode)
        assertFalse(settings.videoPlayerProxyDebugInfoEnabled)
        assertEquals(MpvProfileMode.FAST, settings.mpvProfileMode)
        assertEquals(90, settings.historyRetentionDays)
        assertEquals(200, settings.historyMaxRecords)
    }

    @Test
    fun videoProxySettingsCanBeUpdatedAndReadBack() = runTest {
        val store = createStore("updates.preferences_pb")

        store.updateVideoSeekOptimizationEnabled(false)
        store.updateVideoForwardPrefetchMode(VideoForwardPrefetchMode.AGGRESSIVE)
        store.updateVideoProxyDiagnosticsMode(VideoProxyDiagnosticsMode.DETAIL)
        store.updateVideoPlayerProxyDebugInfoEnabled(true)

        val settings = store.settings.first()

        assertFalse(settings.videoSeekOptimizationEnabled)
        assertEquals(VideoForwardPrefetchMode.AGGRESSIVE, settings.videoForwardPrefetchMode)
        assertEquals(VideoProxyDiagnosticsMode.DETAIL, settings.videoProxyDiagnosticsMode)
        assertTrue(settings.videoPlayerProxyDebugInfoEnabled)
    }

    @Test
    fun mpvProfileModeCanBeUpdatedAndReadBack() = runTest {
        val store = createStore("mpv_profile.preferences_pb")

        store.updateMpvProfileMode(MpvProfileMode.HIGH_QUALITY)

        val settings = store.settings.first()

        assertEquals(MpvProfileMode.HIGH_QUALITY, settings.mpvProfileMode)
    }

    @Test
    fun anime4kDefaultsAreDisabledWithFastModeA() = runTest {
        val store = createStore("anime4k_defaults.preferences_pb")

        val settings = store.settings.first()

        assertFalse(settings.anime4kEnabled)
        assertEquals(Anime4KMode.A, settings.anime4kMode)
        assertEquals(Anime4KQuality.FAST, settings.anime4kQuality)
    }

    @Test
    fun anime4kSettingsCanBeUpdatedAndReadBack() = runTest {
        val store = createStore("anime4k_updates.preferences_pb")

        store.updateAnime4KEnabled(true)
        store.updateAnime4KMode(Anime4KMode.C_PLUS)
        store.updateAnime4KQuality(Anime4KQuality.HIGH)

        val settings = store.settings.first()
        assertTrue(settings.anime4kEnabled)
        assertEquals(Anime4KMode.C_PLUS, settings.anime4kMode)
        assertEquals(Anime4KQuality.HIGH, settings.anime4kQuality)
    }

    @Test
    fun videoLibraryThumbnailsEnabledDefaultsToTrue() = runTest {
        val store = createStore("video_library_thumbnail_default.preferences_pb")

        assertTrue(store.settings.first().videoLibraryThumbnailsEnabled)
    }

    @Test
    fun videoLibraryThumbnailsEnabledCanBeUpdatedAndReadBack() = runTest {
        val store = createStore("video_library_thumbnail_update.preferences_pb")

        store.updateVideoLibraryThumbnailsEnabled(false)

        assertFalse(store.settings.first().videoLibraryThumbnailsEnabled)
    }

    @Test
    fun avifImageSupportDefaultsOffAndCanBeUpdated() = runTest {
        val store = createStore("avif_image_support.preferences_pb")

        assertFalse(store.settings.first().avifImagesEnabled)

        store.updateAvifImagesEnabled(true)

        assertTrue(store.settings.first().avifImagesEnabled)
    }

    @Test
    fun pageImageCacheDefaultsOnAndCanBeUpdated() = runTest {
        val store = createStore("page_image_cache_enabled.preferences_pb")

        assertTrue(store.settings.first().pageImageCacheEnabled)

        store.updatePageImageCacheEnabled(false)

        assertFalse(store.settings.first().pageImageCacheEnabled)
    }

    @Test
    fun readerPinchZoomDefaultsOff() = runTest {
        val store = createStore("reader_pinch_zoom_default.preferences_pb")

        assertFalse(store.settings.first().readerPinchZoomEnabled)
    }

    @Test
    fun readerPinchZoomCanBeUpdatedAndReadBack() = runTest {
        val store = createStore("reader_pinch_zoom_update.preferences_pb")

        store.updateReaderPinchZoomEnabled(true)

        assertTrue(store.settings.first().readerPinchZoomEnabled)
    }

    @Test
    fun historyPolicyCanBeUpdatedAndIsCoercedToSupportedOptions() = runTest {
        val store = createStore("history_policy.preferences_pb")

        store.updateHistoryRetentionDays(31)
        store.updateHistoryMaxRecords(490)

        val settings = store.settings.first()
        assertEquals(30, settings.historyRetentionDays)
        assertEquals(500, settings.historyMaxRecords)
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
