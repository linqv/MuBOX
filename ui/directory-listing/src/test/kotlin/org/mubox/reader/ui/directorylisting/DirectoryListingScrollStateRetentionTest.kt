package org.mubox.reader.ui.directorylisting

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DirectoryListingScrollStateRetentionTest {
    @Test
    fun defaultRetentionRemainsBoundedDuringDeepBrowsing() {
        val retention = DirectoryListingScrollStateRetention()

        repeat(1_000) { index ->
            retention.recordAccess("directory-$index:list")
        }

        assertEquals(MAX_RETAINED_DIRECTORY_SCROLL_STATES, retention.retainedKeys().size)
    }

    @Test
    fun evictsLeastRecentlyUsedKeyAtCapacity() {
        val retention = DirectoryListingScrollStateRetention(maxRetainedStateCount = 3)
        retention.recordAccess("root:list")
        retention.recordAccess("series:list")
        retention.recordAccess("chapter:list")

        assertEquals(listOf("root:list"), retention.recordAccess("page:list"))
        assertEquals(
            setOf("series:list", "chapter:list", "page:list"),
            retention.retainedKeys(),
        )
    }

    @Test
    fun revisitingAKeyRefreshesItsRecency() {
        val retention = DirectoryListingScrollStateRetention(maxRetainedStateCount = 3)
        retention.recordAccess("root:list")
        retention.recordAccess("series:list")
        retention.recordAccess("chapter:list")

        assertTrue(retention.recordAccess("root:list").isEmpty())

        assertEquals(listOf("series:list"), retention.recordAccess("page:list"))
        assertEquals(
            setOf("chapter:list", "root:list", "page:list"),
            retention.retainedKeys(),
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsUnboundedZeroCapacity() {
        DirectoryListingScrollStateRetention(maxRetainedStateCount = 0)
    }
}
