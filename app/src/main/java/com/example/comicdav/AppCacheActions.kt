package com.example.comicdav

import android.content.Context
import com.example.comicdav.data.ComicCacheAnalysis
import com.example.comicdav.data.ComicCacheCategory
import com.example.comicdav.data.analyzeComicCache
import com.example.comicdav.data.clearComicCache
import com.example.comicdav.data.clearComicCacheCategory
import com.example.comicdav.data.formatCacheSize
import com.example.comicdav.feature.reader.ReaderPageCache
import com.example.comicdav.feature.settings.pageCacheLimitBytesForSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal data class AppCacheActionCallbacks(
    val setAnalysis: (ComicCacheAnalysis) -> Unit,
    val setActionMessage: (String?) -> Unit,
)

internal class AppCacheActions(
    private val context: Context,
    private val scope: CoroutineScope,
    private val viewModels: AppViewModels,
    private val callbacks: AppCacheActionCallbacks,
) {
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
                ReaderPageCache.prune(context.cacheDir, maxBytes = pageCacheLimitBytes)
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

    private suspend fun analyze(): ComicCacheAnalysis =
        withContext(Dispatchers.IO) {
            analyzeComicCache(
                cacheDir = context.cacheDir,
                codeCacheDir = context.codeCacheDir,
                externalCacheDirs = context.externalCacheDirs.filterNotNull(),
            )
        }
}
