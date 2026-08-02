package com.example.comicdav

import android.content.Context
import android.net.Uri
import com.example.comicdav.core.model.history.WatchHistoryEntry
import com.example.comicdav.core.model.history.WatchMediaType
import com.example.comicdav.core.model.history.WatchSourceType
import com.example.comicdav.core.model.history.historyThumbnailFile
import com.example.comicdav.core.model.history.historyThumbnailStableKey
import com.example.comicdav.core.model.media.WebDavVideoOpenRequest
import com.example.comicdav.core.model.media.fileDirectoryVideoThumbnailVersion
import com.example.comicdav.core.model.media.mimeTypeForMediaFileName
import com.example.comicdav.core.model.media.webDavVideoThumbnailStableKey
import com.example.comicdav.core.model.settings.AppSettings
import com.example.comicdav.core.remote.RemoteFileInfo
import com.example.comicdav.feature.reader.LocalComicOpener
import com.example.comicdav.feature.videolibrary.VideoThumbnailExtractor
import com.example.comicdav.infrastructure.library.WebDavLibraryCoverExtractor
import com.example.comicdav.core.model.videolibrary.VideoLibraryItemWithSources
import com.example.comicdav.core.model.videolibrary.VideoSourceType
import com.example.comicdav.video.proxy.VideoProxyManager
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Owns thumbnail extraction and cache materialization.
 *
 * UI state and repository mutations stay in [AppVideoActions]; this class only
 * coordinates media infrastructure needed to produce an image file.
 */
internal class AppVideoThumbnailLoader(
    private val context: Context,
    private val settings: AppSettings,
    private val videoThumbnailExtractor: VideoThumbnailExtractor,
    private val localComicOpener: LocalComicOpener,
    private val coverExtractor: WebDavLibraryCoverExtractor,
    private val webDavResolver: AppWebDavResolver,
    private val videoProxyManager: VideoProxyManager,
) {
    suspend fun extractLocal(
        uri: String,
        size: Long?,
        lastModified: Long?,
        forceRefresh: Boolean = false,
        stableKey: String = fileDirectoryVideoThumbnailVersion(
            uri = uri,
            size = size,
            lastModified = lastModified,
        ),
    ): String? =
        videoThumbnailExtractor.extractFromContentUri(
            context = context,
            uri = Uri.parse(uri),
            stableKey = stableKey,
            forceRefresh = forceRefresh,
        )

    suspend fun extractWebDav(
        request: WebDavVideoOpenRequest,
        forceRefresh: Boolean = false,
    ): String? =
        extractWebDav(
            request = request,
            stableKey = webDavStableKey(request),
            forceRefresh = forceRefresh,
        )

    suspend fun extractWebDav(
        request: WebDavVideoOpenRequest,
        stableKey: String,
        forceRefresh: Boolean = false,
    ): String? {
        if (!forceRefresh) {
            videoThumbnailExtractor.cachedThumbnailPath(stableKey)?.let { return it }
        }
        val clientFactory = webDavResolver.clientFactoryForPlayback(request.accountId)
            ?: error("缺少 WebDAV 账号，请重新连接后再提取缩略图")
        val session = videoProxyManager.open(
            request = request.copy(subtitles = emptyList()),
            clientFactory = clientFactory,
            proxySettings = settings.toVideoProxySettings(),
        )
        return try {
            videoThumbnailExtractor.extractFromUrl(
                url = session.url,
                stableKey = stableKey,
                forceRefresh = forceRefresh,
            )
        } finally {
            videoProxyManager.close(session)
        }
    }

    suspend fun extractVideoLibrary(
        item: VideoLibraryItemWithSources,
        forceRefresh: Boolean = false,
    ): String =
        when (item.item.sourceType) {
            VideoSourceType.LOCAL -> {
                val source = item.localSource ?: error("缺少本地视频来源")
                extractLocal(
                    uri = source.uri,
                    size = source.size,
                    lastModified = source.lastModified,
                    forceRefresh = forceRefresh,
                ) ?: error("未能提取视频缩略图")
            }
            VideoSourceType.WEBDAV -> {
                val source = item.webDavSource ?: error("缺少 WebDAV 视频来源")
                extractWebDav(
                    request = WebDavVideoOpenRequest(
                        accountId = source.accountId,
                        remotePath = source.remotePath,
                        displayName = source.fileName,
                        size = source.size,
                        etag = source.etag,
                        lastModified = source.lastModified,
                        mimeType = mimeTypeForMediaFileName(source.fileName),
                    ),
                    forceRefresh = forceRefresh,
                ) ?: error("未能提取视频缩略图")
            }
        }

    suspend fun extractHistory(entry: WatchHistoryEntry): String =
        when (entry.mediaType) {
            WatchMediaType.VIDEO -> extractHistoryVideo(entry)
            WatchMediaType.COMIC -> extractHistoryComic(entry)
        }

    fun webDavStableKey(request: WebDavVideoOpenRequest): String =
        webDavVideoThumbnailStableKey(
            accountId = request.accountId,
            remotePath = request.remotePath,
            size = request.size,
            etag = request.etag,
            lastModified = request.lastModified,
        )

    private suspend fun extractHistoryVideo(entry: WatchHistoryEntry): String {
        val stableKey = historyThumbnailStableKey(entry)
        return when (entry.sourceType) {
            WatchSourceType.LOCAL ->
                videoThumbnailExtractor.extractFromContentUri(
                    context = context,
                    uri = Uri.parse(entry.sourceLocator),
                    stableKey = stableKey,
                ) ?: error("未能提取历史视频缩略图")
            WatchSourceType.WEB_DAV -> {
                val accountId = entry.accountId ?: error("历史记录缺少 WebDAV 账号")
                extractWebDav(
                    request = WebDavVideoOpenRequest(
                        accountId = accountId,
                        remotePath = entry.sourceLocator,
                        displayName = entry.displayTitle,
                        size = entry.size,
                        etag = entry.etag,
                        lastModified = entry.lastModified,
                        mimeType = mimeTypeForMediaFileName(entry.displayTitle),
                    ),
                    stableKey = stableKey,
                ) ?: error("未能提取历史视频缩略图")
            }
        }
    }

    private suspend fun extractHistoryComic(entry: WatchHistoryEntry): String {
        val target = historyThumbnailFile(context.cacheDir, entry)
        return when (entry.sourceType) {
            WatchSourceType.LOCAL -> withContext(Dispatchers.IO) {
                val session = localComicOpener.open(
                    uri = Uri.parse(entry.sourceLocator),
                    fileName = entry.displayTitle,
                    avifImagesEnabled = effectiveAvifImagesEnabled(settings.avifImagesEnabled),
                )
                val temporary = File(target.parentFile, "${target.name}.tmp")
                try {
                    check(session.pageCount > 0) { "历史漫画没有可用页面" }
                    target.parentFile?.mkdirs()
                    temporary.delete()
                    val loaded = session.loadPageToFile(0, temporary)
                    moveIntoPlace(loaded, target)
                } finally {
                    runCatching { session.close() }
                    temporary.delete()
                }
            }
            WatchSourceType.WEB_DAV -> {
                val accountId = entry.accountId ?: error("历史记录缺少 WebDAV 账号")
                val client = webDavResolver.clientFor(accountId)
                    ?: error("请先连接 $accountId，再提取历史漫画缩略图")
                val coverPath = coverExtractor.extractFirstPageCover(
                    client = client,
                    accountId = accountId,
                    remotePath = entry.sourceLocator,
                    avifImagesEnabled = effectiveAvifImagesEnabled(settings.avifImagesEnabled),
                    knownInfo = entry.size?.let { size ->
                        RemoteFileInfo(
                            path = entry.sourceLocator,
                            size = size,
                            etag = entry.etag,
                            lastModified = entry.lastModified,
                            supportsRange = true,
                        )
                    },
                ) ?: error("未能提取历史漫画缩略图")
                withContext(Dispatchers.IO) {
                    val temporary = File(target.parentFile, "${target.name}.tmp")
                    try {
                        target.parentFile?.mkdirs()
                        temporary.delete()
                        File(coverPath).copyTo(temporary, overwrite = true)
                        moveIntoPlace(temporary, target)
                    } finally {
                        temporary.delete()
                    }
                }
            }
        }
    }

    private fun moveIntoPlace(source: File, target: File): String {
        check(source.isFile && source.length() > 0L) { "提取的历史缩略图为空" }
        target.parentFile?.mkdirs()
        if (target.exists()) target.delete()
        if (!source.renameTo(target)) {
            source.copyTo(target, overwrite = true)
            source.delete()
        }
        return target.absolutePath
    }
}
