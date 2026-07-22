package com.example.comicdav.video.player

import com.example.comicdav.feature.filedirectory.FileDirectoryBrowserItem
import com.example.comicdav.network.WebDavItem
import com.example.comicdav.video.LocalVideoOpenRequest
import com.example.comicdav.video.MediaKind
import com.example.comicdav.video.VideoSubtitleOpenRequest
import com.example.comicdav.video.WebDavSubtitleOpenRequest
import com.example.comicdav.video.WebDavVideoOpenRequest
import com.example.comicdav.video.findSidecarSubtitles
import com.example.comicdav.video.mediaKindFor
import com.example.comicdav.video.mimeTypeForMediaFileName

internal fun buildLocalDirectoryEpisodeQueue(
    entries: List<FileDirectoryBrowserItem>,
    currentItem: FileDirectoryBrowserItem,
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

internal fun buildWebDavDirectoryEpisodeQueue(
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

internal fun localVideoEpisodeRequest(
    video: FileDirectoryBrowserItem,
    directoryEntries: List<FileDirectoryBrowserItem>,
): LocalVideoOpenRequest =
    LocalVideoOpenRequest(
        uri = video.uri,
        displayName = video.name,
        size = video.size,
        lastModified = video.lastModified,
        subtitles = findSidecarSubtitles(
            videoFileName = video.name,
            candidates = directoryEntries,
            nameOf = FileDirectoryBrowserItem::name,
            isDirectoryOf = FileDirectoryBrowserItem::isDirectory,
        ).map { subtitle ->
            VideoSubtitleOpenRequest(
                uri = subtitle.uri,
                displayName = subtitle.name,
            )
        },
    )

internal fun webDavVideoEpisodeRequest(
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
