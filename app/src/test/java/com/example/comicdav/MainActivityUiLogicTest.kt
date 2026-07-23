package com.example.comicdav

import com.example.comicdav.feature.downloads.DownloadProgressThrottler
import com.example.comicdav.feature.downloads.localDownloadFileNameForRemoteFile
import android.content.pm.ActivityInfo
import androidx.compose.ui.graphics.luminance
import com.example.comicdav.core.model.settings.AppColorPalette
import com.example.comicdav.ui.comicDavColorSchemeFor
import com.example.comicdav.ui.comicDavTypography
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MainActivityUiLogicTest {
    @Test
    fun readerOverlayOnlyPlacesTheVisibleLayer() {
        assertEquals(0, readerOverlayVisibleLayer(readerOpen = false))
        assertEquals(1, readerOverlayVisibleLayer(readerOpen = true))
    }

    @Test
    fun appTabsIncludeDownloadsBetweenVideoLibraryAndSettings() {
        assertEquals(
            listOf("来源", "书架", "影视库", "下载", "设置"),
            appTabLabels(),
        )
    }

    @Test
    fun localDirectoryVideoSelectionActionsOnlyAddToVideoLibraryAndCancel() {
        assertEquals(
            listOf("加入影视库", "取消"),
            selectionActionLabelsForLocalVideo(),
        )
    }

    @Test
    fun webDavVideoSelectionActionsAddToVideoLibraryDownloadAndCancel() {
        assertEquals(
            listOf("加入影视库", "下载", "取消"),
            selectionActionLabelsForWebDavVideo(),
        )
    }

    @Test
    fun videoLibrarySelectionActionsManageThumbnailAndRemoveOnly() {
        assertEquals(
            listOf("重新提取缩略图", "移除", "删除缩略图", "取消"),
            selectionActionLabelsForVideoLibraryItem(),
        )
    }

    @Test
    fun downloadsScreenNoLongerHasMultiSelectSelection() {
        assertEquals(
            true,
            hasActiveAppSelection(
                webDavFileSelected = false,
                directoryComicSelected = false,
                directoryVideoSelected = false,
                libraryItemSelected = true,
                videoLibraryItemSelected = false,
            ),
        )
        assertEquals(
            false,
            hasActiveAppSelection(
                webDavFileSelected = false,
                directoryComicSelected = false,
                directoryVideoSelected = false,
                libraryItemSelected = false,
                videoLibraryItemSelected = false,
            ),
        )
    }

    @Test
    fun videoDownloadRecordIsKeptWhenDocumentDeleteReturnsFalse() {
        assertEquals(
            false,
            shouldRemoveVideoDownloadRecordAfterDelete(
                documentDeleteSucceeded = false,
                documentStillResolvable = true,
            ),
        )
    }

    @Test
    fun localDownloadFileNameIncludesPathSpecificSuffixBeforeExtension() {
        val first = localDownloadFileNameForRemoteFile(
            accountId = "account-1",
            remotePath = "/series-a/01.cbz",
            fileName = "01.cbz",
        )
        val second = localDownloadFileNameForRemoteFile(
            accountId = "account-1",
            remotePath = "/series-b/01.cbz",
            fileName = "01.cbz",
        )

        assertNotEquals(first, second)
        assertTrue(first.startsWith("01-"))
        assertTrue(first.endsWith(".cbz"))
        assertTrue(second.startsWith("01-"))
        assertTrue(second.endsWith(".cbz"))
    }

    @Test
    fun localDownloadFileNameKeepsSanitizedFallbackWhenNameHasNoExtension() {
        val localName = localDownloadFileNameForRemoteFile(
            accountId = "account-1",
            remotePath = "/downloads/invalid:name",
            fileName = "invalid:name",
        )

        assertTrue(localName.startsWith("invalid_name-"))
    }

    @Test
    fun downloadLocalUriTextOrNullRejectsMissingLocalUri() {
        assertNull(downloadLocalUriTextOrNull(null))
        assertNull(downloadLocalUriTextOrNull("   "))
        assertEquals("content://downloads/root/demo.cbz", downloadLocalUriTextOrNull(" content://downloads/root/demo.cbz "))
    }

    @Test
    fun deleteOutcomeRemovesRecordWhenFileAlreadyMissing() {
        assertTrue(
            shouldRemoveDownloadRecordAfterDelete(
                documentDeleteSucceeded = false,
                documentStillResolvable = false,
            ),
        )
    }

    @Test
    fun defaultThemeUsesAdwaitaDarkShellRoles() {
        val colors = comicDavColorSchemeFor(AppColorPalette.DEFAULT)

        assertTrue("default background should be a dark shell", colors.background.luminance() < 0.10f)
        assertTrue("surface should layer above background", colors.surface.luminance() >= colors.background.luminance())
        assertTrue(
            "high surface containers should layer above low containers",
            colors.surfaceContainerHigh.luminance() > colors.surfaceContainerLow.luminance(),
        )
        assertTrue("text on background should stay readable", colors.onBackground.luminance() > 0.70f)
        assertTrue("primary should read as a blue accent", colors.primary.blue > colors.primary.red)
        assertTrue("secondary should read as a purple accent", colors.secondary.blue > colors.secondary.green)
        assertTrue("tertiary should read as an amber accent", colors.tertiary.red > colors.tertiary.blue)
        assertTrue("error pair should have sufficient contrast", colors.error.luminance() != colors.onError.luminance())
        assertTrue(
            "error container pair should work on dark UI",
            colors.errorContainer.luminance() < colors.onErrorContainer.luminance(),
        )
    }

    @Test
    fun compactTypographyAvoidsDecorativeTracking() {
        val typography = comicDavTypography()

        listOf(
            typography.titleSmall,
            typography.bodySmall,
            typography.labelLarge,
            typography.labelMedium,
            typography.labelSmall,
        ).forEach { style ->
            val ls = style.letterSpacing
            assertTrue(
                "letterSpacing should be unspecified or non-negative",
                ls == androidx.compose.ui.unit.TextUnit.Unspecified || ls.value >= 0f,
            )
        }
    }

    @Test
    fun appShellUsesMuBoxMediaSurfaceRoles() {
        val colors = comicDavColorSchemeFor(AppColorPalette.DEFAULT)
        val muBoxColors = com.example.comicdav.ui.muBoxColorsFor(colors)

        assertEquals(muBoxColors.background, appShellBackgroundColor(colors))
        assertEquals(muBoxColors.panel, appShellNavigationBarContainerColor(colors))
        assertEquals(muBoxColors.panelHigh, appShellNavigationBarIndicatorColor(colors))
        assertEquals(muBoxColors.panelHigh, selectionNavigationBarContainerColor(colors))
    }

    @Test
    fun mainAppOrientationPolicyOnlyLocksWhenReaderRotationLockIsEnabled() {
        assertEquals(
            ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED,
            mainAppRequestedOrientation(screenRotationLockEnabled = false),
        )
        assertEquals(
            ActivityInfo.SCREEN_ORIENTATION_LOCKED,
            mainAppRequestedOrientation(screenRotationLockEnabled = true),
        )
    }

    @Test
    fun readerLandscapeModeOverridesGlobalOrientationOnlyWhileReaderIsOpen() {
        assertEquals(
            ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE,
            comicDavRequestedOrientation(
                screenRotationLockEnabled = false,
                isReaderOpen = true,
                readerLandscapeModeEnabled = true,
            ),
        )
        assertEquals(
            ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE,
            comicDavRequestedOrientation(
                screenRotationLockEnabled = true,
                isReaderOpen = true,
                readerLandscapeModeEnabled = true,
            ),
        )
        assertEquals(
            ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED,
            comicDavRequestedOrientation(
                screenRotationLockEnabled = false,
                isReaderOpen = false,
                readerLandscapeModeEnabled = true,
            ),
        )
        assertEquals(
            ActivityInfo.SCREEN_ORIENTATION_LOCKED,
            comicDavRequestedOrientation(
                screenRotationLockEnabled = true,
                isReaderOpen = false,
                readerLandscapeModeEnabled = true,
            ),
        )
    }

    @Test
    fun readerLandscapeModeCanLockSensorRotation() {
        assertEquals(
            ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE,
            comicDavRequestedOrientation(
                screenRotationLockEnabled = false,
                isReaderOpen = true,
                readerLandscapeModeEnabled = true,
                readerLandscapeOrientationLocked = true,
            ),
        )
    }

    @Test
    fun requestedOrientationUpdatesOnlyWhenTargetChanges() {
        assertFalse(
            shouldUpdateRequestedOrientation(
                current = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT,
                target = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT,
            ),
        )
        assertTrue(
            shouldUpdateRequestedOrientation(
                current = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED,
                target = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT,
            ),
        )
    }

    @Test
    fun openingPortraitReaderDoesNotChangeRequestedOrientation() {
        val closedOrientation = comicDavRequestedOrientation(
            screenRotationLockEnabled = false,
            isReaderOpen = false,
            readerLandscapeModeEnabled = false,
        )
        val openOrientation = comicDavRequestedOrientation(
            screenRotationLockEnabled = false,
            isReaderOpen = true,
            readerLandscapeModeEnabled = false,
        )

        assertEquals(closedOrientation, openOrientation)
        assertFalse(shouldUpdateRequestedOrientation(closedOrientation, openOrientation))
    }

    @Test
    fun forcedPortraitClearsOnlyAfterMainScreenIsBackInPortrait() {
        assertTrue(
            shouldClearForcedMainPortrait(
                forceMainPortrait = true,
                isReaderOpen = false,
                configurationOrientation = android.content.res.Configuration.ORIENTATION_PORTRAIT,
            ),
        )
        assertFalse(
            shouldClearForcedMainPortrait(
                forceMainPortrait = true,
                isReaderOpen = true,
                configurationOrientation = android.content.res.Configuration.ORIENTATION_PORTRAIT,
            ),
        )
        assertFalse(
            shouldClearForcedMainPortrait(
                forceMainPortrait = true,
                isReaderOpen = false,
                configurationOrientation = android.content.res.Configuration.ORIENTATION_LANDSCAPE,
            ),
        )
    }

    @Test
    fun readerBackTargetPreservesNavigationPriority() {
        assertEquals(
            AppBackTarget.CLOSE_READER,
            appBackTarget(
                hasActiveSelection = false,
                isReaderOpen = true,
                isWebDavOpen = true,
                hasOpenFileDirectory = true,
                selectedTab = AppTab.LIBRARY,
            ),
        )
        assertEquals(
            AppBackTarget.CLEAR_SELECTION,
            appBackTarget(
                hasActiveSelection = true,
                isReaderOpen = true,
                isWebDavOpen = true,
                hasOpenFileDirectory = true,
                selectedTab = AppTab.LIBRARY,
            ),
        )
        assertEquals(
            AppBackTarget.NONE,
            appBackTarget(
                hasActiveSelection = false,
                isReaderOpen = false,
                isWebDavOpen = false,
                hasOpenFileDirectory = false,
                selectedTab = AppTab.SOURCES,
            ),
        )
    }

    @Test
    fun leavingReaderLandscapeRequestsPortraitRestore() {
        assertEquals(
            true,
            shouldForcePortraitAfterReaderLandscapeModeChange(
                currentReaderLandscapeModeEnabled = true,
                nextReaderLandscapeModeEnabled = false,
            ),
        )
        assertEquals(
            false,
            shouldForcePortraitAfterReaderLandscapeModeChange(
                currentReaderLandscapeModeEnabled = false,
                nextReaderLandscapeModeEnabled = true,
            ),
        )
    }

    @Test
    fun closingReaderClearsTemporaryLandscapeMode() {
        assertEquals(false, readerLandscapeModeAfterReaderClosed())
    }

    @Test
    fun avifReaderSupportRequiresSettingAndAndroid14OrNewer() {
        assertEquals(false, effectiveAvifImagesEnabled(settingEnabled = false, sdkInt = 34))
        assertEquals(false, effectiveAvifImagesEnabled(settingEnabled = true, sdkInt = 33))
        assertEquals(true, effectiveAvifImagesEnabled(settingEnabled = true, sdkInt = 34))
    }

    @Test
    fun downloadProgressThrottlerCoalescesSmallFrequentUpdates() {
        val throttler = DownloadProgressThrottler(
            minIntervalMillis = 250L,
            minByteDelta = 1024L,
        )

        assertEquals(true, throttler.shouldReport(downloadedBytes = 512L, totalBytes = 4096L, nowMillis = 0L))
        assertEquals(false, throttler.shouldReport(downloadedBytes = 768L, totalBytes = 4096L, nowMillis = 100L))
        assertEquals(true, throttler.shouldReport(downloadedBytes = 1600L, totalBytes = 4096L, nowMillis = 120L))
        assertEquals(true, throttler.shouldReport(downloadedBytes = 1700L, totalBytes = 4096L, nowMillis = 400L))
    }

    @Test
    fun webDavParentDirectoryKeepsEncodedPathForRemoteRequests() {
        assertEquals("/", parentWebDavDirectoryPath("/movie.mp4"))
        assertEquals("/%E8%A7%86%E9%A2%91/", parentWebDavDirectoryPath("/%E8%A7%86%E9%A2%91/movie.mp4"))
    }

}
