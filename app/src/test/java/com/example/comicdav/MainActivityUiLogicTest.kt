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
}
