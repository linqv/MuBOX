package com.example.comicdav.feature.reader

import org.junit.Assert.assertEquals
import org.junit.Test

class ReaderScreenTest {
    @Test
    fun topBarActionsExposeLandscapeBeforeLogAndClose() {
        assertEquals(
            listOf("横屏", "日志", "关闭"),
            readerTopBarActionLabels(readerLandscapeModeEnabled = false),
        )
    }

    @Test
    fun topBarActionsExposeExitLandscapeWhenEnabled() {
        assertEquals(
            listOf("退出横屏", "锁定方向", "日志", "关闭"),
            readerTopBarActionLabels(readerLandscapeModeEnabled = true),
        )
    }

    @Test
    fun topBarActionsExposeUnlockWhenLandscapeOrientationIsLocked() {
        assertEquals(
            listOf("退出横屏", "解锁方向", "日志", "关闭"),
            readerTopBarActionLabels(
                readerLandscapeModeEnabled = true,
                readerLandscapeOrientationLocked = true,
            ),
        )
    }

    @Test
    fun landscapeButtonTogglesCurrentMode() {
        assertEquals(true, readerLandscapeModeButtonTarget(readerLandscapeModeEnabled = false))
        assertEquals(false, readerLandscapeModeButtonTarget(readerLandscapeModeEnabled = true))
    }

    @Test
    fun landscapeOrientationLockButtonTogglesCurrentMode() {
        assertEquals(true, readerLandscapeOrientationLockButtonTarget(readerLandscapeOrientationLocked = false))
        assertEquals(false, readerLandscapeOrientationLockButtonTarget(readerLandscapeOrientationLocked = true))
    }
}
