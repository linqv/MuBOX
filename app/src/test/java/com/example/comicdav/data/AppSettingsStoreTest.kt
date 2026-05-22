package com.example.comicdav.data

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.example.comicdav.video.player.MpvProfileMode
import com.example.comicdav.video.proxy.VideoForwardPrefetchMode
import com.example.comicdav.video.proxy.VideoProxyDiagnosticsMode
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
        assertEquals(MpvProfileMode.FAST, settings.mpvProfileMode)
    }

    @Test
    fun videoProxySettingsCanBeUpdatedAndReadBack() = runTest {
        val store = createStore("updates.preferences_pb")

        store.updateVideoSeekOptimizationEnabled(false)
        store.updateVideoForwardPrefetchMode(VideoForwardPrefetchMode.AGGRESSIVE)
        store.updateVideoProxyDiagnosticsMode(VideoProxyDiagnosticsMode.DETAIL)

        val settings = store.settings.first()

        assertFalse(settings.videoSeekOptimizationEnabled)
        assertEquals(VideoForwardPrefetchMode.AGGRESSIVE, settings.videoForwardPrefetchMode)
        assertEquals(VideoProxyDiagnosticsMode.DETAIL, settings.videoProxyDiagnosticsMode)
    }

    @Test
    fun mpvProfileModeCanBeUpdatedAndReadBack() = runTest {
        val store = createStore("mpv_profile.preferences_pb")

        store.updateMpvProfileMode(MpvProfileMode.HIGH_QUALITY)

        val settings = store.settings.first()

        assertEquals(MpvProfileMode.HIGH_QUALITY, settings.mpvProfileMode)
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

    private fun TestScope.createStore(fileName: String): AppSettingsStore {
        val preferencesFile = temporaryFolder.newFile(fileName)
        val dataStore = PreferenceDataStoreFactory.create(
            scope = backgroundScope,
            produceFile = { preferencesFile },
        )
        return AppSettingsStore(dataStore)
    }
}
