package com.example.comicdav

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HistoryPruneTriggerTest {
    @Test
    fun progressOnlyUpdateDoesNotCountAsHistoryAddition() {
        val existing = setOf("comic", "video")

        assertFalse(hasHistoryEntryAdditions(existing, existing))
    }

    @Test
    fun newMediaKeyCountsAsHistoryAddition() {
        assertTrue(
            hasHistoryEntryAdditions(
                previousMediaKeys = setOf("comic"),
                currentMediaKeys = setOf("comic", "video"),
            ),
        )
    }

    @Test
    fun deletionDoesNotCountAsHistoryAddition() {
        assertFalse(
            hasHistoryEntryAdditions(
                previousMediaKeys = setOf("comic", "video"),
                currentMediaKeys = setOf("video"),
            ),
        )
    }
}
