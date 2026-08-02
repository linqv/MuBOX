package org.mubox.reader.feature.webdav

import org.mubox.reader.core.remote.WebDavItem
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class WebDavDirectoryMemoryCacheTest {
    @Test
    fun evictsLeastRecentlyUsedDirectoryWhenDirectoryLimitExceeded() {
        val cache = WebDavDirectoryMemoryCache()
        repeat(20) { index ->
            cache.put("/$index/", listOf(directoryItem(index)))
        }
        assertNotNull(cache.get("/0/"))

        cache.put("/20/", listOf(directoryItem(20)))

        assertNull(cache.get("/1/"))
        assertNotNull(cache.get("/0/"))
        assertNotNull(cache.get("/20/"))
    }

    @Test
    fun doesNotCacheDirectoryLargerThanTotalItemBudget() {
        val cache = WebDavDirectoryMemoryCache()

        cache.put("/huge/", List(10_001) { index -> directoryItem(index) })

        assertNull(cache.get("/huge/"))
    }

    @Test
    fun evictsLeastRecentlyUsedDirectoriesToStayWithinTotalItemBudget() {
        val cache = WebDavDirectoryMemoryCache()
        cache.put("/old/", List(6_000) { index -> directoryItem(index) })
        cache.put("/recent/", List(4_000) { index -> directoryItem(index + 6_000) })

        cache.put("/new/", listOf(directoryItem(10_000)))

        assertNull(cache.get("/old/"))
        assertNotNull(cache.get("/recent/"))
        assertNotNull(cache.get("/new/"))
    }

    @Test
    fun expiresDirectoryAfterTwoMinutes() {
        var nowMillis = 1_000L
        val cache = WebDavDirectoryMemoryCache(nowMillis = { nowMillis })
        cache.put("/Comics/", listOf(directoryItem(1)))

        nowMillis += 120_000L

        assertNull(cache.get("/Comics/"))
    }

    private fun directoryItem(index: Int): WebDavItem =
        WebDavItem(
            name = "Directory $index",
            path = "/$index/",
            isDirectory = true,
            size = null,
            etag = null,
            lastModified = null,
        )
}
