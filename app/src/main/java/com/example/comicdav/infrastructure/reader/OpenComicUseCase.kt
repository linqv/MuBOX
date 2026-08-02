package com.example.comicdav.infrastructure.reader

import com.example.comicdav.core.diagnostics.DiagnosticCategory
import com.example.comicdav.core.diagnostics.Diagnostics
import com.example.comicdav.core.diagnostics.NoopDiagnostics
import com.example.comicdav.core.model.media.readerImageFormatCacheKey
import com.example.comicdav.core.ports.ReadingProgressGateway
import com.example.comicdav.core.ports.RemoteRangeComicSessionFactory
import com.example.comicdav.core.remote.RemoteFileInfo
import com.example.comicdav.core.remote.WebDavClient
import com.example.comicdav.data.ComicCacheKey
import com.example.comicdav.data.ComicDownloadCache
import com.example.comicdav.feature.reader.OpenComicResult
import com.example.comicdav.nativebridge.ComicEngine
import com.example.comicdav.nativebridge.RangeProviderRegistry
import com.example.comicdav.network.WebDavRangeProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class OpenComicUseCase(
    private val accountId: String,
    private val cache: ComicDownloadCache,
    private val progressStore: ReadingProgressGateway,
    private val diagnostics: Diagnostics = NoopDiagnostics,
    private val openRemoteSession: RemoteRangeComicSessionFactory = {
            fileId,
            size,
            cacheDir,
            comicKey,
            validator,
            avifImagesEnabled,
            webDavPrefetchPageCount,
        ->
        ComicEngine().openRemote(
            fileId = fileId,
            size = size,
            cacheDir = cacheDir,
            comicKey = comicKey,
            validator = validator,
            avifImagesEnabled = avifImagesEnabled,
            webDavPrefetchPageCount = webDavPrefetchPageCount,
        )
    },
    private val avifImagesEnabled: Boolean = false,
    private val webDavPrefetchPageCount: Int = 4,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    suspend fun open(
        client: WebDavClient,
        remotePath: String,
        knownInfo: RemoteFileInfo? = null,
    ): OpenComicResult = withContext(ioDispatcher) {
        val info = knownInfo ?: client.head(remotePath)
        val key = ComicCacheKey.fromRemote(
            accountId = accountId,
            remotePath = remotePath,
            size = info.size,
            etag = info.etag,
            lastModified = info.lastModified,
        )
        try {
            openRemote(client, remotePath, info, key)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            diagnostics.error(
                DiagnosticCategory.SESSION,
                "open_remote_range_failed path=$remotePath size=${info.size}",
                error,
            )
            throw error
        }
    }

    private suspend fun openRemote(
        client: WebDavClient,
        remotePath: String,
        info: RemoteFileInfo,
        key: ComicCacheKey,
    ): OpenComicResult {
        cache.cacheDir.mkdirs()
        val fileId = RangeProviderRegistry.register(
            WebDavRangeProvider(client, remotePath, info.size),
        )
        return try {
            // open() dispatches the full preparation path to IO before reaching this worker-thread call.
            val session = openRemoteSession(
                fileId,
                info.size,
                cache.cacheDir,
                key.value,
                info.validator(),
                avifImagesEnabled,
                webDavPrefetchPageCount,
            )
            val initialPage = progressStore.loadPage(key.value).coerceIn(0, (session.pageCount - 1).coerceAtLeast(0))
            OpenComicResult(
                comicKey = key.value,
                pageCacheKey = readerImageFormatCacheKey(key.value, avifImagesEnabled),
                localFile = cache.cacheDir.resolve("${key.value}.remote"),
                session = session,
                initialPage = initialPage,
            )
        } catch (error: Throwable) {
            RangeProviderRegistry.unregister(fileId)
            throw error
        }
    }

    private fun RemoteFileInfo.validator(): String =
        etag ?: lastModified?.toString() ?: size.toString()
}
