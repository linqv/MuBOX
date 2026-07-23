package com.example.comicdav.video.player

import com.example.comicdav.core.model.media.LocalVideoOpenRequest
import com.example.comicdav.core.model.media.MediaEntry
import com.example.comicdav.core.model.media.MediaKind
import com.example.comicdav.core.model.media.VideoSubtitleOpenRequest
import com.example.comicdav.core.model.media.WebDavSubtitleOpenRequest
import com.example.comicdav.core.model.media.WebDavVideoOpenRequest
import com.example.comicdav.core.model.media.findSidecarSubtitles
import com.example.comicdav.core.model.media.mediaKindFor
import com.example.comicdav.core.model.media.mimeTypeForMediaFileName
import com.example.comicdav.core.remote.WebDavItem

fun buildLocalDirectoryEpisodeQueue(
    entries: List<MediaEntry>,
    currentItem: MediaEntry,
): VideoEpisodeQueue? {
    val videos = entries.filter { it.mediaKind == MediaKind.Video }
    val subtitles = entries.filter { it.mediaKind == MediaKind.Subtitle }
    val currentIndex = videos.indexOfFirst { it.uri == currentItem.uri }
    if (currentIndex < 0) return null
    val episodes = videos.map { video ->
        VideoEpisode.local(localVideoEpisodeRequest(video, subtitles))
    }
    return VideoEpisodeQueue(episodes = episodes, currentIndex = currentIndex)
}

fun buildWebDavDirectoryEpisodeQueue(
    accountId: String,
    items: List<WebDavItem>,
    currentItem: WebDavItem,
): VideoEpisodeQueue? {
    val videos = items.filter { item ->
        mediaKindFor(name = item.name, isDirectory = item.isDirectory) == MediaKind.Video
    }
    val subtitles = items.filter { item ->
        mediaKindFor(name = item.name, isDirectory = item.isDirectory) == MediaKind.Subtitle
    }
    val currentIndex = videos.indexOfFirst { it.path == currentItem.path }
    if (currentIndex < 0) return null
    val episodes = videos.map { video ->
        VideoEpisode.webDav(webDavVideoEpisodeRequest(accountId, video, subtitles))
    }
    return VideoEpisodeQueue(episodes = episodes, currentIndex = currentIndex)
}

fun localVideoEpisodeRequest(
    video: MediaEntry,
    directoryEntries: List<MediaEntry>,
): LocalVideoOpenRequest =
    LocalVideoOpenRequest(
        uri = video.uri,
        displayName = video.name,
        size = video.size,
        lastModified = video.lastModified,
        subtitles = findSidecarSubtitles(
            videoFileName = video.name,
            candidates = directoryEntries,
            nameOf = MediaEntry::name,
            isDirectoryOf = MediaEntry::isDirectory,
        ).map { subtitle ->
            VideoSubtitleOpenRequest(
                uri = subtitle.uri,
                displayName = subtitle.name,
            )
        },
    )

fun webDavVideoEpisodeRequest(
    accountId: String,
    video: WebDavItem,
    directoryItems: List<WebDavItem>,
): WebDavVideoOpenRequest =
    WebDavVideoOpenRequest(
        accountId = accountId,
        remotePath = video.path,
        displayName = video.name,
        size = video.size,
        etag = video.etag,
        lastModified = video.lastModified,
        mimeType = mimeTypeForMediaFileName(video.name),
        subtitles = findSidecarSubtitles(
            videoFileName = video.name,
            candidates = directoryItems,
            nameOf = WebDavItem::name,
            isDirectoryOf = WebDavItem::isDirectory,
        ).map { subtitle ->
            WebDavSubtitleOpenRequest(
                remotePath = subtitle.path,
                displayName = subtitle.name,
                size = subtitle.size,
                etag = subtitle.etag,
                lastModified = subtitle.lastModified,
                mimeType = mimeTypeForMediaFileName(subtitle.name),
            )
        },
    )
