package com.example.comicdav.video.player

import com.example.comicdav.video.LocalVideoOpenRequest
import com.example.comicdav.video.WebDavVideoOpenRequest
import java.util.LinkedHashMap
import java.util.UUID

enum class VideoEpisodeSource {
    LOCAL,
    WEB_DAV,
}

data class VideoEpisode(
    val localRequest: LocalVideoOpenRequest? = null,
    val webDavRequest: WebDavVideoOpenRequest? = null,
) {
    init {
        require((localRequest == null) != (webDavRequest == null)) {
            "A video episode must contain exactly one playback request"
        }
    }

    val source: VideoEpisodeSource
        get() = if (localRequest != null) VideoEpisodeSource.LOCAL else VideoEpisodeSource.WEB_DAV

    val displayName: String
        get() = localRequest?.displayName ?: requireNotNull(webDavRequest).displayName

    val playbackKey: String
        get() = localRequest?.let { request ->
            localVideoPlaybackKey(
                uri = request.uri,
                size = request.size,
                lastModified = request.lastModified,
            )
        } ?: requireNotNull(webDavRequest).let { request ->
            webDavVideoPlaybackKey(
                accountId = request.accountId,
                remotePath = request.remotePath,
                size = request.size,
                etag = request.etag,
                lastModified = request.lastModified,
            )
        }

    companion object {
        fun local(request: LocalVideoOpenRequest): VideoEpisode = VideoEpisode(localRequest = request)

        fun webDav(request: WebDavVideoOpenRequest): VideoEpisode = VideoEpisode(webDavRequest = request)
    }
}

class VideoEpisodeQueue(
    val episodes: List<VideoEpisode>,
    currentIndex: Int = 0,
) {
    init {
        require(episodes.distinctBy(VideoEpisode::playbackKey).size == episodes.size) {
            "Episode playback keys must be stable and unique"
        }
    }

    val currentIndex: Int = currentIndex.coerceInEpisodes(episodes)

    val currentEpisode: VideoEpisode?
        get() = episodes.getOrNull(currentIndex)

    val hasPrevious: Boolean
        get() = currentIndex > 0 && episodes.isNotEmpty()

    val hasNext: Boolean
        get() = currentIndex < episodes.lastIndex

    fun indexOf(playbackKey: String?): Int =
        playbackKey?.let { key -> episodes.indexOfFirst { it.playbackKey == key } } ?: -1

    fun withCurrentPlaybackKey(playbackKey: String?): VideoEpisodeQueue {
        val matchingIndex = indexOf(playbackKey)
        return if (matchingIndex >= 0) VideoEpisodeQueue(episodes, matchingIndex) else this
    }
}

private fun Int.coerceInEpisodes(episodes: List<VideoEpisode>): Int =
    if (episodes.isEmpty()) 0 else coerceIn(0, episodes.lastIndex)

internal object VideoEpisodeQueueRegistry {
    private const val MAX_ACTIVE_QUEUES = 8
    private val queues = LinkedHashMap<String, VideoEpisodeQueue>()

    @Synchronized
    fun register(queue: VideoEpisodeQueue): String {
        while (queues.size >= MAX_ACTIVE_QUEUES) {
            val oldestQueueId = queues.keys.firstOrNull() ?: break
            queues.remove(oldestQueueId)
        }
        return UUID.randomUUID().toString().also { queueId ->
            queues[queueId] = queue
        }
    }

    @Synchronized
    fun consume(queueId: String?): VideoEpisodeQueue? =
        queueId?.let(queues::remove)

    @Synchronized
    internal fun clearForTests() {
        queues.clear()
    }
}
