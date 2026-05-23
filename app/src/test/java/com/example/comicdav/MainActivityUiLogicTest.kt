package com.example.comicdav

import android.content.pm.ActivityInfo
import org.junit.Assert.assertEquals
import org.junit.Test

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
}
