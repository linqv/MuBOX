package com.example.comicdav.ui.directorylisting

const val MAX_RETAINED_DIRECTORY_SCROLL_STATES: Int = 64

/**
 * Tracks directory scroll-state keys in least-recently-used order.
 *
 * The returned keys are safe for callers to remove from a [androidx.compose.runtime.saveable.SaveableStateHolder].
 */
class DirectoryListingScrollStateRetention(
    private val maxRetainedStateCount: Int = MAX_RETAINED_DIRECTORY_SCROLL_STATES,
) {
    private val retainedKeys = LinkedHashSet<String>()

    init {
        require(maxRetainedStateCount > 0) { "maxRetainedStateCount must be positive" }
    }

    fun recordAccess(key: String): List<String> {
        retainedKeys.remove(key)
        retainedKeys.add(key)

        if (retainedKeys.size <= maxRetainedStateCount) return emptyList()

        val evictedKeys = ArrayList<String>(retainedKeys.size - maxRetainedStateCount)
        val iterator = retainedKeys.iterator()
        while (retainedKeys.size > maxRetainedStateCount && iterator.hasNext()) {
            val evictedKey = iterator.next()
            iterator.remove()
            evictedKeys += evictedKey
        }
        return evictedKeys
    }

    fun retainedKeys(): Set<String> = retainedKeys.toSet()
}
