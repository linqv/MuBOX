package com.example.comicdav

import android.content.Context
import com.example.comicdav.core.model.history.WatchHistoryEntry
import com.example.comicdav.core.model.history.WatchMediaType
import com.example.comicdav.core.model.history.WatchSourceType
import com.example.comicdav.data.ComicCacheKey
import com.example.comicdav.core.model.cache.ComicCacheAnalysis
import com.example.comicdav.core.model.cache.ComicCacheCategory
import com.example.comicdav.data.analyzeComicCache
import com.example.comicdav.data.clearComicCache
import com.example.comicdav.data.clearComicCacheCategory
import com.example.comicdav.core.model.format.formatCacheSize
import com.example.comicdav.feature.reader.clearReaderPageCacheForComic
import com.example.comicdav.feature.reader.pruneReaderPageCache
import com.example.comicdav.feature.settings.pageCacheLimitBytesForSettings
import com.example.comicdav.core.model.history.historyThumbnailFile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

internal data class AppCacheActionCallbacks(
    val setAnalysis: (ComicCacheAnalysis) -> Unit,
    val setActionMessage: (String?) -> Unit,
)

internal class AppCacheActions(
    private val context: Context,
    private val scope: CoroutineScope,
    private val container: AppContainer,
    private val viewModels: AppViewModels,
    private val callbacks: AppCacheActionCallbacks,
) {
    private val historyPruneMutex = Mutex()

    fun refresh() {
        scope.launch {
            refreshNow()
        }
    }

    suspend fun refreshNow() {
        callbacks.setAnalysis(analyze())
    }

    suspend fun applyReaderPageCacheSettings(
        pageImageCacheEnabled: Boolean,
        diskCacheLimitMb: Int,
    ) {
        val pageCacheLimitBytes = pageCacheLimitBytesForSettings(
            pageImageCacheEnabled = pageImageCacheEnabled,
            limitMb = diskCacheLimitMb,
        )
        viewModels.reader.updatePageImageCacheEnabled(pageImageCacheEnabled)
        viewModels.reader.updatePageCacheMaxBytes(pageCacheLimitBytes)
        withContext(Dispatchers.IO) {
            if (pageImageCacheEnabled) {
                pruneReaderPageCache(context.cacheDir, maxBytes = pageCacheLimitBytes)
            } else {
                clearComicCacheCategory(context.cacheDir, ComicCacheCategory.READER_PAGES)
            }
        }
        refresh()
    }

    fun clearCategory(category: ComicCacheCategory) {
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                clearComicCacheCategory(
                    cacheDir = context.cacheDir,
                    category = category,
                    codeCacheDir = context.codeCacheDir,
                    externalCacheDirs = context.externalCacheDirs.filterNotNull(),
                )
            }
            refreshNow()
            callbacks.setActionMessage(
                "已清理 ${category.cacheLabel()}：${result.filesDeleted} 个文件，释放 ${formatCacheSize(result.bytesDeleted)}",
            )
        }
    }

    fun clearAll() {
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                clearComicCache(
                    cacheDir = context.cacheDir,
                    codeCacheDir = context.codeCacheDir,
                    externalCacheDirs = context.externalCacheDirs.filterNotNull(),
                )
            }
            refreshNow()
            callbacks.setActionMessage(
                "已清理全部缓存：${result.filesDeleted} 个文件，释放 ${formatCacheSize(result.bytesDeleted)}",
            )
        }
    }

    fun deleteHistoryEntry(entry: WatchHistoryEntry) {
        scope.launch {
            val bytesDeleted = deleteHistoryEntryNow(entry)
            callbacks.setActionMessage("已删除历史记录并清理 ${formatCacheSize(bytesDeleted)} 关联缓存")
            refreshNow()
        }
    }

    fun clearHistory() {
        scope.launch {
            val entries = container.watchHistoryRepository.history.first()
            val bytesDeleted = withContext(Dispatchers.IO) {
                entries.sumOf { entry -> deleteHistoryEntryCaches(entry) }
            }
            container.watchHistoryRepository.clear()
            container.progressStore.clear()
            container.videoPlaybackStateStore.clearAll()
            callbacks.setActionMessage(
                "已清空 ${entries.size} 条观看历史并清理 ${formatCacheSize(bytesDeleted)} 关联缓存",
            )
            refreshNow()
        }
    }

    suspend fun pruneHistory(retentionDays: Int, maxRecords: Int) {
        historyPruneMutex.withLock {
            val removed = container.watchHistoryRepository.prune(retentionDays, maxRecords)
            if (removed.isEmpty()) return@withLock
            withContext(Dispatchers.IO) {
                removed.forEach(::deleteHistoryEntryCaches)
            }
            removed.forEach { entry ->
                if (entry.mediaType == WatchMediaType.COMIC) {
                    container.progressStore.deletePage(entry.mediaKey)
                } else {
                    container.videoPlaybackStateStore.savePosition(entry.mediaKey, 0L, 0L)
                }
            }
            refreshNow()
        }
    }

    private suspend fun deleteHistoryEntryNow(entry: WatchHistoryEntry): Long {
        container.watchHistoryRepository.delete(entry.mediaKey)
        if (entry.mediaType == WatchMediaType.COMIC) {
            container.progressStore.deletePage(entry.mediaKey)
        } else {
            container.videoPlaybackStateStore.savePosition(entry.mediaKey, 0L, 0L)
        }
        return withContext(Dispatchers.IO) { deleteHistoryEntryCaches(entry) }
    }

    private fun deleteHistoryEntryCaches(entry: WatchHistoryEntry): Long {
        // Video thumbnails are content-addressed and shared by the source grid,
        // video library, and history. Removing one history row must not delete
        // artwork that can still be referenced by either of the other surfaces.
        if (entry.mediaType == WatchMediaType.VIDEO) return 0L
        val thumbnailFile = historyThumbnailFile(context.cacheDir, entry)
        val thumbnailBytes = thumbnailFile.length().takeIf { thumbnailFile.isFile } ?: 0L
        thumbnailFile.delete()
        val readerPagesBytes = clearReaderPageCacheForComic(
            cacheDir = context.cacheDir,
            comicKey = entry.mediaKey,
        )
        val remoteBytes = if (entry.sourceType == WatchSourceType.WEB_DAV) {
            container.remoteCache.clear(ComicCacheKey(entry.mediaKey))
        } else {
            0L
        }
        return thumbnailBytes + readerPagesBytes + remoteBytes
    }

    private suspend fun analyze(): ComicCacheAnalysis =
        withContext(Dispatchers.IO) {
            analyzeComicCache(
                cacheDir = context.cacheDir,
                codeCacheDir = context.codeCacheDir,
                externalCacheDirs = context.externalCacheDirs.filterNotNull(),
            )
        }
}
