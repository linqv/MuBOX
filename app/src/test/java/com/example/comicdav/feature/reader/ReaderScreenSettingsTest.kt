package com.example.comicdav.feature.reader

import androidx.compose.ui.input.key.Key
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ReaderScreenSettingsTest {
    @Test
    fun autoPageTargetsNextPageWhenPagerIsIdle() {
        assertEquals(3, autoPageTargetPage(currentPage = 2, pageCount = 5, isScrollInProgress = false))
    }

    @Test
    fun autoPageDoesNotAdvanceWhileScrollingOrAtLastPage() {
        assertNull(autoPageTargetPage(currentPage = 2, pageCount = 5, isScrollInProgress = true))
        assertNull(autoPageTargetPage(currentPage = 4, pageCount = 5, isScrollInProgress = false))
    }

    @Test
    fun volumeKeysMapToAdjacentPagesWithinBounds() {
        assertEquals(3, volumeKeyTargetPage(currentPage = 2, pageCount = 5, key = Key.VolumeDown))
        assertEquals(1, volumeKeyTargetPage(currentPage = 2, pageCount = 5, key = Key.VolumeUp))
        assertEquals(0, volumeKeyTargetPage(currentPage = 0, pageCount = 5, key = Key.VolumeUp))
        assertEquals(4, volumeKeyTargetPage(currentPage = 4, pageCount = 5, key = Key.VolumeDown))
    }

    @Test
    fun nonVolumeKeysAreIgnored() {
        assertNull(volumeKeyTargetPage(currentPage = 2, pageCount = 5, key = Key.DirectionDown))
    }
}
