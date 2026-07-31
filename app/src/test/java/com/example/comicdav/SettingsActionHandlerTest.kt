package com.example.comicdav

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.example.comicdav.core.model.history.WatchHistoryEntry
import com.example.comicdav.core.model.history.WatchMediaType
import com.example.comicdav.core.model.history.WatchSourceType
import com.example.comicdav.core.model.settings.Anime4KProfile
import com.example.comicdav.core.model.settings.AppColorPalette
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
import com.example.comicdav.data.AppSettingsStore
import com.example.comicdav.core.model.cache.ComicCacheCategory
import com.example.comicdav.feature.settings.SettingsAction
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

@OptIn(ExperimentalCoroutinesApi::class)
class SettingsActionHandlerTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun groupedSettingActionsPersistTheirPayloads() = runTest {
        val store = createStore("settings_action_dispatch.preferences_pb")
        val reader = ReaderSettings(
            readingDirection = ReadingDirection.VERTICAL_CONTINUOUS,
            readerLoggingMode = ReaderLoggingMode.DETAIL,
            avifImagesEnabled = true,
            autoPageEnabled = true,
            autoPageSpeedMillis = 12_000,
            volumeKeysTurnPagesEnabled = true,
            readerPinchZoomEnabled = true,
        )
        val appearance = AppearanceSettings(
            colorPalette = AppColorPalette.SEPIA,
            screenRotationLockEnabled = true,
            libraryCoversEnabled = false,
        )
        val storage = StorageSettings(
            pageImageCacheEnabled = false,
            diskCacheLimitMb = 2048,
            webDavPrefetchPageCount = 8,
        )
        val video = VideoSettings(
            videoResumeEnabled = false,
            videoSeekOptimizationEnabled = false,
            videoForwardPrefetchMode = VideoForwardPrefetchMode.AGGRESSIVE,
            videoProxyDiagnosticsMode = VideoProxyDiagnosticsMode.DETAIL,
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
        val history = HistorySettings(
            historyRetentionDays = 180,
            historyMaxRecords = 500,
        )
        val actions = listOf(
            SettingsAction.UpdateReader { reader },
            SettingsAction.UpdateAppearance { appearance },
            SettingsAction.UpdateStorage { storage },
            SettingsAction.UpdateVideo { video },
            SettingsAction.UpdateHistory { history },
        )
        val handler = SettingsActionHandler(
            appSettingsStore = store,
            scope = this,
            onClearCacheCategory = {},
            onClearAllCache = {},
        )

        actions.forEach(handler::handle)
        advanceUntilIdle()

        val settings = store.settings.first()
        assertEquals(reader, settings.reader)
        assertEquals(appearance, settings.appearance)
        assertEquals(storage, settings.storage)
        assertEquals(video, settings.video)
        assertEquals(history, settings.history)
    }

    @Test
    fun consecutiveMutationsInOneGroupComposeAgainstLatestPersistedValue() = runTest {
        val store = createStore("settings_action_mutations.preferences_pb")
        val handler = SettingsActionHandler(
            appSettingsStore = store,
            scope = this,
            onClearCacheCategory = {},
            onClearAllCache = {},
        )

        handler.handle(
            SettingsAction.UpdateReader { current ->
                current.copy(autoPageEnabled = true)
            },
        )
        handler.handle(
            SettingsAction.UpdateReader { current ->
                current.copy(readerPinchZoomEnabled = true)
            },
        )
        handler.handle(
            SettingsAction.UpdateVideo { current ->
                current.copy(videoResumeEnabled = false)
            },
        )
        handler.handle(
            SettingsAction.UpdateVideo { current ->
                current.copy(gridVideoThumbnailsEnabled = false)
            },
        )
        advanceUntilIdle()

        val settings = store.settings.first()
        assertEquals(true, settings.reader.autoPageEnabled)
        assertEquals(true, settings.reader.readerPinchZoomEnabled)
        assertEquals(false, settings.video.videoResumeEnabled)
        assertEquals(false, settings.video.gridVideoThumbnailsEnabled)
    }

    @Test
    fun cacheActionsInvokeOnlyTheirMatchingCallbacks() = runTest {
        val store = createStore("cache_action_dispatch.preferences_pb")
        val clearedCategories = mutableListOf<ComicCacheCategory>()
        var clearAllCount = 0

        val handler = SettingsActionHandler(
            appSettingsStore = store,
            scope = this,
            onClearCacheCategory = clearedCategories::add,
            onClearAllCache = { clearAllCount += 1 },
        )

        handler.handle(SettingsAction.ClearCacheCategory(ComicCacheCategory.REMOTE_INDEX))
        handler.handle(SettingsAction.ClearAllCache)

        assertEquals(listOf(ComicCacheCategory.REMOTE_INDEX), clearedCategories)
        assertEquals(1, clearAllCount)
    }

    @Test
    fun historyActionsInvokeOnlyTheirMatchingCallbacks() = runTest {
        val store = createStore("history_action_dispatch.preferences_pb")
        val entry = WatchHistoryEntry(
            mediaKey = "comic",
            mediaType = WatchMediaType.COMIC,
            title = "Comic",
            sourceType = WatchSourceType.LOCAL,
            sourceLocator = "/comic.cbz",
            progress = 1,
            total = 2,
            lastWatchedAt = 3,
        )
        val deletedEntries = mutableListOf<WatchHistoryEntry>()
        var clearHistoryCount = 0
        val handler = SettingsActionHandler(
            appSettingsStore = store,
            scope = this,
            onClearCacheCategory = {},
            onClearAllCache = {},
            onDeleteHistoryEntry = deletedEntries::add,
            onClearHistory = { clearHistoryCount += 1 },
        )

        handler.handle(SettingsAction.DeleteHistoryEntry(entry))
        handler.handle(SettingsAction.ClearHistory)

        assertEquals(listOf(entry), deletedEntries)
        assertEquals(1, clearHistoryCount)
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
