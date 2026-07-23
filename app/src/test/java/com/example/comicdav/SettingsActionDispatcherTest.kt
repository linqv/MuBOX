package com.example.comicdav

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.example.comicdav.core.model.settings.Anime4KMode
import com.example.comicdav.core.model.settings.Anime4KQuality
import com.example.comicdav.core.model.settings.AppColorPalette
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
import com.example.comicdav.data.AppSettingsStore
import com.example.comicdav.data.ComicCacheCategory
import com.example.comicdav.feature.settings.SettingsAction
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

@OptIn(ExperimentalCoroutinesApi::class)
class SettingsActionDispatcherTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun settingActionsPersistTheirPayloads() = runTest {
        val store = createStore("settings_action_dispatch.preferences_pb")
        val actions = listOf(
            SettingsAction.SetReadingDirection(ReadingDirection.VERTICAL_CONTINUOUS),
            SettingsAction.SetReaderLoggingMode(ReaderLoggingMode.DETAIL),
            SettingsAction.SetColorPalette(AppColorPalette.SEPIA),
            SettingsAction.SetAvifImagesEnabled(true),
            SettingsAction.SetAutoPageEnabled(true),
            SettingsAction.SetAutoPageSpeedMillis(12_000),
            SettingsAction.SetScreenRotationLockEnabled(true),
            SettingsAction.SetVolumeKeysTurnPagesEnabled(true),
            SettingsAction.SetReaderPinchZoomEnabled(true),
            SettingsAction.SetPageImageCacheEnabled(false),
            SettingsAction.SetDiskCacheLimitMb(2048),
            SettingsAction.SetWebDavPrefetchPageCount(8),
            SettingsAction.SetLibraryCoversEnabled(false),
            SettingsAction.SetVideoResumeEnabled(false),
            SettingsAction.SetVideoBackgroundMode(VideoBackgroundMode.BACKGROUND_PLAY),
            SettingsAction.SetVideoSeekOptimizationEnabled(false),
            SettingsAction.SetVideoForwardPrefetchMode(VideoForwardPrefetchMode.AGGRESSIVE),
            SettingsAction.SetVideoProxyDiagnosticsMode(VideoProxyDiagnosticsMode.DETAIL),
            SettingsAction.SetVideoPlayerProxyDebugInfoEnabled(true),
            SettingsAction.SetVideoOutputMode(VideoOutputMode.GPU_NEXT),
            SettingsAction.SetGpuApiMode(GpuApiMode.VULKAN),
            SettingsAction.SetAnime4KEnabled(true),
            SettingsAction.SetAnime4KMode(Anime4KMode.C_PLUS),
            SettingsAction.SetAnime4KQuality(Anime4KQuality.HIGH),
            SettingsAction.SetVideoDecoderMode(VideoDecoderMode.SOFTWARE),
            SettingsAction.SetMpvProfileMode(MpvProfileMode.HIGH_QUALITY),
            SettingsAction.SetVideoControlsAutoHideMillis(10_000),
            SettingsAction.SetVideoPlayerOrientationMode(VideoPlayerOrientationMode.LANDSCAPE),
            SettingsAction.SetVideoLibraryThumbnailsEnabled(false),
        )

        actions.forEach { action ->
            dispatchSettingsAction(
                action = action,
                appSettingsStore = store,
                scope = this,
                onClearCacheCategory = {},
                onClearAllCache = {},
            )
        }
        advanceUntilIdle()

        val settings = store.settings.first()
        assertEquals(ReadingDirection.VERTICAL_CONTINUOUS, settings.readingDirection)
        assertEquals(ReaderLoggingMode.DETAIL, settings.readerLoggingMode)
        assertEquals(AppColorPalette.SEPIA, settings.colorPalette)
        assertTrue(settings.avifImagesEnabled)
        assertTrue(settings.autoPageEnabled)
        assertEquals(12_000, settings.autoPageSpeedMillis)
        assertTrue(settings.screenRotationLockEnabled)
        assertTrue(settings.volumeKeysTurnPagesEnabled)
        assertTrue(settings.readerPinchZoomEnabled)
        assertFalse(settings.pageImageCacheEnabled)
        assertEquals(2048, settings.diskCacheLimitMb)
        assertEquals(8, settings.webDavPrefetchPageCount)
        assertFalse(settings.libraryCoversEnabled)
        assertFalse(settings.videoResumeEnabled)
        assertEquals(VideoBackgroundMode.BACKGROUND_PLAY, settings.videoBackgroundMode)
        assertFalse(settings.videoSeekOptimizationEnabled)
        assertEquals(VideoForwardPrefetchMode.AGGRESSIVE, settings.videoForwardPrefetchMode)
        assertEquals(VideoProxyDiagnosticsMode.DETAIL, settings.videoProxyDiagnosticsMode)
        assertTrue(settings.videoPlayerProxyDebugInfoEnabled)
        assertEquals(VideoOutputMode.GPU_NEXT, settings.videoOutputMode)
        assertEquals(GpuApiMode.VULKAN, settings.gpuApiMode)
        assertTrue(settings.anime4kEnabled)
        assertEquals(Anime4KMode.C_PLUS, settings.anime4kMode)
        assertEquals(Anime4KQuality.HIGH, settings.anime4kQuality)
        assertEquals(VideoDecoderMode.SOFTWARE, settings.videoDecoderMode)
        assertEquals(MpvProfileMode.HIGH_QUALITY, settings.mpvProfileMode)
        assertEquals(10_000, settings.videoControlsAutoHideMillis)
        assertEquals(VideoPlayerOrientationMode.LANDSCAPE, settings.videoPlayerOrientationMode)
        assertFalse(settings.videoLibraryThumbnailsEnabled)
    }

    @Test
    fun cacheActionsInvokeOnlyTheirMatchingCallbacks() = runTest {
        val store = createStore("cache_action_dispatch.preferences_pb")
        val clearedCategories = mutableListOf<ComicCacheCategory>()
        var clearAllCount = 0

        dispatchSettingsAction(
            action = SettingsAction.ClearCacheCategory(ComicCacheCategory.REMOTE_INDEX),
            appSettingsStore = store,
            scope = this,
            onClearCacheCategory = clearedCategories::add,
            onClearAllCache = { clearAllCount += 1 },
        )
        dispatchSettingsAction(
            action = SettingsAction.ClearAllCache,
            appSettingsStore = store,
            scope = this,
            onClearCacheCategory = clearedCategories::add,
            onClearAllCache = { clearAllCount += 1 },
        )

        assertEquals(listOf(ComicCacheCategory.REMOTE_INDEX), clearedCategories)
        assertEquals(1, clearAllCount)
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
