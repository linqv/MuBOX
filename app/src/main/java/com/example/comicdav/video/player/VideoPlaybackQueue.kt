package com.example.comicdav.video.player

data class VideoQueueItem(
    val playbackKey: String,
    val displayName: String,
    val sourceUri: String,
    val source: VideoQueueSource,
)

enum class VideoQueueSource {
    LOCAL,
    WEB_DAV,
}

class VideoPlaybackQueue(
    val items: List<VideoQueueItem>,
    currentIndex: Int = 0,
    val autoPlayNext: Boolean = false,
) {
    init {
        require(items.distinctBy { it.playbackKey }.size == items.size) {
            "Queue playback keys must be stable and unique"
        }
    }

    val currentIndex: Int = currentIndex.coerceInQueue(items)

    val currentItem: VideoQueueItem?
        get() = items.getOrNull(currentIndex)

    val hasPrevious: Boolean
        get() = currentIndex > 0 && items.isNotEmpty()

    val hasNext: Boolean
        get() = currentIndex < items.lastIndex

    fun previousItem(): VideoQueueItem? = items.getOrNull(currentIndex - 1)

    fun nextItem(): VideoQueueItem? = items.getOrNull(currentIndex + 1)

    fun movePrevious(): VideoPlaybackQueue =
        copy(currentIndex = (currentIndex - 1).coerceAtLeast(0))

    fun moveNext(): VideoPlaybackQueue =
        copy(currentIndex = (currentIndex + 1).coerceAtMost(items.lastIndex.coerceAtLeast(0)))

    fun withCurrentItem(playbackKey: String): VideoPlaybackQueue {
        val index = items.indexOfFirst { it.playbackKey == playbackKey }
        return if (index >= 0) copy(currentIndex = index) else this
    }

    private fun copy(
        currentIndex: Int = this.currentIndex,
        autoPlayNext: Boolean = this.autoPlayNext,
    ): VideoPlaybackQueue =
        VideoPlaybackQueue(
            items = items,
            currentIndex = currentIndex.coerceInQueue(items),
            autoPlayNext = autoPlayNext,
        )

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is VideoPlaybackQueue) return false
        return items == other.items &&
            currentIndex == other.currentIndex &&
            autoPlayNext == other.autoPlayNext
    }

    override fun hashCode(): Int {
        var result = items.hashCode()
        result = 31 * result + currentIndex
        result = 31 * result + autoPlayNext.hashCode()
        return result
    }

    override fun toString(): String =
        "VideoPlaybackQueue(items=$items, currentIndex=$currentIndex, autoPlayNext=$autoPlayNext)"
}

data class VideoQueueSession(
    val queue: VideoPlaybackQueue,
    val playbackUri: String,
    val webDavStreamIds: List<String> = emptyList(),
)

data class VideoQueueOpenResult(
    val playbackUri: String,
    val webDavStreamIds: List<String> = emptyList(),
)

class VideoQueueSwitcher(
    private val closeWebDavStreams: (List<String>) -> Unit,
    private val openQueueItem: (VideoQueueItem) -> VideoQueueOpenResult,
) {
    fun switchTo(
        currentSession: VideoQueueSession,
        targetQueue: VideoPlaybackQueue,
    ): VideoQueueSession {
        val targetItem = targetQueue.currentItem ?: return currentSession
        if (currentSession.webDavStreamIds.isNotEmpty()) {
            closeWebDavStreams(currentSession.webDavStreamIds)
        }
        val opened = openQueueItem(targetItem)
        return VideoQueueSession(
            queue = targetQueue,
            playbackUri = opened.playbackUri,
            webDavStreamIds = opened.webDavStreamIds,
        )
    }
}

private fun Int.coerceInQueue(items: List<VideoQueueItem>): Int =
    when {
        items.isEmpty() -> 0
        else -> coerceIn(0, items.lastIndex)
    }
