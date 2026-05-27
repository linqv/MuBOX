package com.example.comicdav

import android.content.pm.ActivityInfo
import androidx.compose.ui.graphics.luminance
import com.example.comicdav.data.AppColorPalette
import com.example.comicdav.ui.comicDavColorSchemeFor
import com.example.comicdav.ui.comicDavTypography
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class MainActivityUiLogicTest {
    @Test
    fun appTabsIncludeVideoLibraryBetweenLibraryAndSettings() {
        assertEquals(
            listOf("来源", "书架", "影视库", "设置"),
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
    fun defaultThemeUsesCinematicDarkShellRoles() {
        val colors = comicDavColorSchemeFor(AppColorPalette.DEFAULT)

        assertTrue("default background should be a dark shell", colors.background.luminance() < 0.05f)
        assertTrue("surface should layer above background", colors.surface.luminance() > colors.background.luminance())
        assertTrue(
            "high surface containers should layer above low containers",
            colors.surfaceContainerHigh.luminance() > colors.surfaceContainerLow.luminance(),
        )
        assertTrue("text on background should stay readable", colors.onBackground.luminance() > 0.70f)
        assertTrue("primary should read as a cyan media accent", colors.primary.blue > colors.primary.red)
        assertTrue("primary should read as a cyan media accent", colors.primary.green > colors.primary.red)
        assertTrue("secondary should read as a purple accent", colors.secondary.blue > colors.secondary.green)
        assertTrue("secondary should read as a purple accent", colors.secondary.red > colors.secondary.green)
        assertTrue("tertiary should read as an amber accent", colors.tertiary.red > colors.tertiary.blue)
        assertTrue("tertiary should read as an amber accent", colors.tertiary.green > colors.tertiary.blue)
        assertTrue("error pair should work on dark UI", colors.error.luminance() > colors.onError.luminance())
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
            assertTrue(style.letterSpacing.value >= 0f)
            assertTrue(style.letterSpacing.value <= 0.1f)
        }
    }

    @Test
    fun appShellUsesMuBoxMediaSurfaceRoles() {
        val colors = comicDavColorSchemeFor(AppColorPalette.DEFAULT)
        val muBoxColors = com.example.comicdav.ui.muBoxColorsFor(colors)

        assertEquals(muBoxColors.background, appShellBackgroundColor(colors))
        assertEquals(muBoxColors.panel, appShellNavigationBarContainerColor(colors))
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

    @Test
    fun mainActivityCompositionRootStaysBelow100Kb() {
        val file = File("src/main/java/com/example/comicdav/MainActivity.kt")
        assertTrue(
            "MainActivity.kt should stay below 100 KB after composition-root extraction; actual=${file.length()}",
            file.length() < 100_000L,
        )
    }
}
