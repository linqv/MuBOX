package org.mubox.reader

import android.net.Uri
import org.mubox.reader.core.model.media.LocalVideoOpenRequest
import org.mubox.reader.core.model.media.MediaEntry
import org.mubox.reader.core.model.media.WebDavVideoOpenRequest
import org.mubox.reader.core.remote.WebDavClient
import org.mubox.reader.core.remote.WebDavItem
import org.mubox.reader.feature.filedirectory.LocalDirectoryReader
import org.mubox.reader.ui.directorylisting.DirectorySortDirection
import org.mubox.reader.ui.directorylisting.DirectorySortField
import org.mubox.reader.ui.directorylisting.filterAndSortDirectoryEntries
import org.mubox.reader.video.player.VideoEpisode
import org.mubox.reader.video.player.VideoEpisodeQueue
import org.mubox.reader.video.player.buildLocalDirectoryEpisodeQueue
import org.mubox.reader.video.player.buildWebDavDirectoryEpisodeQueue
import org.mubox.reader.video.player.localVideoEpisodeRequest
import org.mubox.reader.video.player.webDavVideoEpisodeRequest
import kotlinx.coroutines.CancellationException

internal data class LocalDirectoryPlaybackResolution(
    val request: LocalVideoOpenRequest,
    val episodeQueue: VideoEpisodeQueue?,
)

internal data class WebDavDirectoryPlaybackResolution(
    val request: WebDavVideoOpenRequest,
    val episodeQueue: VideoEpisodeQueue?,
)

internal suspend fun resolveLocalDirectoryPlayback(
    localDirectoryReader: LocalDirectoryReader,
    request: LocalVideoOpenRequest,
    onDirectoryReadFailure: (Throwable) -> Unit = {},
): LocalDirectoryPlaybackResolution {
    val parentUri = parentDocumentUriForLocalVideo(Uri.parse(request.uri))
        ?: return LocalDirectoryPlaybackResolution(request, null)
    val entries = try {
        localDirectoryReader.listChildren(parentUri.toString())
    } catch (error: CancellationException) {
        throw error
    } catch (error: Throwable) {
        onDirectoryReadFailure(error)
        return LocalDirectoryPlaybackResolution(request, null)
    }.sortedForPlayback(MediaEntry::name, MediaEntry::size)

    val currentItem = entries.firstOrNull { it.uri == request.uri }
        ?: return LocalDirectoryPlaybackResolution(
            request = request.withDirectorySubtitles(
                currentName = request.displayName,
                entries = entries,
            ),
            episodeQueue = null,
        )
    val currentRequest = request.withDirectorySubtitles(
        currentName = currentItem.name,
        entries = entries,
    )
    val episodeQueue = buildLocalDirectoryEpisodeQueue(entries, currentItem)
        ?.replacingCurrent(VideoEpisode.local(currentRequest))
    return LocalDirectoryPlaybackResolution(
        request = episodeQueue?.currentEpisode?.localRequest ?: currentRequest,
        episodeQueue = episodeQueue,
    )
}

internal suspend fun resolveWebDavDirectoryPlayback(
    client: WebDavClient?,
    request: WebDavVideoOpenRequest,
    onDirectoryReadFailure: (Throwable) -> Unit = {},
): WebDavDirectoryPlaybackResolution {
    if (client == null) return WebDavDirectoryPlaybackResolution(request, null)
    val parentPath = parentWebDavDirectoryPath(request.remotePath)
    val items = try {
        client.list(parentPath)
    } catch (error: CancellationException) {
        throw error
    } catch (error: Throwable) {
        onDirectoryReadFailure(error)
        return WebDavDirectoryPlaybackResolution(request, null)
    }.sortedForPlayback(WebDavItem::name, WebDavItem::size)

    val currentItem = items.firstOrNull { it.path == request.remotePath }
        ?: return WebDavDirectoryPlaybackResolution(
            request = request.withDirectorySubtitles(
                currentName = request.displayName,
                items = items,
            ),
            episodeQueue = null,
        )
    val currentRequest = request.withDirectorySubtitles(
        currentName = currentItem.name,
        items = items,
    )
    val episodeQueue = buildWebDavDirectoryEpisodeQueue(request.accountId, items, currentItem)
        ?.replacingCurrent(VideoEpisode.webDav(currentRequest))
    return WebDavDirectoryPlaybackResolution(
        request = episodeQueue?.currentEpisode?.webDavRequest ?: currentRequest,
        episodeQueue = episodeQueue,
    )
}

private fun LocalVideoOpenRequest.withDirectorySubtitles(
    currentName: String,
    entries: List<MediaEntry>,
): LocalVideoOpenRequest {
    val current = MediaEntry(
        name = currentName,
        uri = uri,
        isDirectory = false,
        size = size,
        lastModified = lastModified,
    )
    return copy(subtitles = localVideoEpisodeRequest(current, entries).subtitles)
}

private fun WebDavVideoOpenRequest.withDirectorySubtitles(
    currentName: String,
    items: List<WebDavItem>,
): WebDavVideoOpenRequest {
    val current = WebDavItem(
        name = currentName,
        path = remotePath,
        isDirectory = false,
        size = size,
        etag = etag,
        lastModified = lastModified,
    )
    return copy(subtitles = webDavVideoEpisodeRequest(accountId, current, items).subtitles)
}

private fun VideoEpisodeQueue.replacingCurrent(episode: VideoEpisode): VideoEpisodeQueue {
    val updatedEpisodes = episodes.toMutableList().apply {
        this[currentIndex] = episode
    }
    return VideoEpisodeQueue(updatedEpisodes, currentIndex)
}

private fun <T> List<T>.sortedForPlayback(
    nameOf: (T) -> String,
    sizeOf: (T) -> Long?,
): List<T> = filterAndSortDirectoryEntries(
    entries = this,
    query = "",
    sortField = DirectorySortField.NAME,
    sortDirection = DirectorySortDirection.ASCENDING,
    nameOf = nameOf,
    sizeOf = sizeOf,
)
