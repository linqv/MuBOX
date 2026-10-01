package org.mubox.reader.feature.webdav

import org.mubox.reader.core.remote.WebDavItem

internal class WebDavDirectoryMemoryCache(
    private val nowMillis: () -> Long = { System.nanoTime() / 1_000_000L },
) {
    private val entries = LinkedHashMap<String, CacheEntry>(DEFAULT_MAX_DIRECTORIES, 0.75f, true)
    private var totalItems: Int = 0

    fun get(path: String): Snapshot? {
        val entry = entries[path] ?: return null
        val ageMillis = nowMillis() - entry.cachedAtMillis
        // Keep bounded stale entries available while the network refresh is in flight.
        return Snapshot(entry.items, isStale = ageMillis >= DEFAULT_TTL_MILLIS)
    }

    fun put(path: String, items: List<WebDavItem>) {
        entries.remove(path)?.let { previous ->
            totalItems -= previous.items.size
        }
        if (items.size > DEFAULT_MAX_ITEMS) return
        entries[path] = CacheEntry(items = items, cachedAtMillis = nowMillis())
        totalItems += items.size
        while (entries.size > DEFAULT_MAX_DIRECTORIES || totalItems > DEFAULT_MAX_ITEMS) {
            val eldestPath = entries.entries.first().key
            totalItems -= entries.remove(eldestPath)?.items?.size ?: 0
        }
    }

    fun clear() {
        entries.clear()
        totalItems = 0
    }

    private data class CacheEntry(
        val items: List<WebDavItem>,
        val cachedAtMillis: Long,
    )

    data class Snapshot(val items: List<WebDavItem>, val isStale: Boolean)

    private companion object {
        const val DEFAULT_MAX_DIRECTORIES = 20
        const val DEFAULT_MAX_ITEMS = 10_000
        const val DEFAULT_TTL_MILLIS = 120_000L
    }
}
