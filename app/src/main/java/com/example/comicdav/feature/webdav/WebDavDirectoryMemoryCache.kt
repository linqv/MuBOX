package com.example.comicdav.feature.webdav

import com.example.comicdav.network.WebDavItem

internal class WebDavDirectoryMemoryCache(
    private val nowMillis: () -> Long = { System.nanoTime() / 1_000_000L },
) {
    private val entries = LinkedHashMap<String, CacheEntry>(DEFAULT_MAX_DIRECTORIES, 0.75f, true)
    private var totalItems: Int = 0

    fun get(path: String): List<WebDavItem>? {
        val entry = entries[path] ?: return null
        val ageMillis = nowMillis() - entry.cachedAtMillis
        if (ageMillis >= DEFAULT_TTL_MILLIS) {
            entries.remove(path)
            totalItems -= entry.items.size
            return null
        }
        return entry.items
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

    private companion object {
        const val DEFAULT_MAX_DIRECTORIES = 20
        const val DEFAULT_MAX_ITEMS = 10_000
        const val DEFAULT_TTL_MILLIS = 120_000L
    }
}
