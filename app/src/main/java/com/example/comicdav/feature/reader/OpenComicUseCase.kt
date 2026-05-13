package com.example.comicdav.feature.reader

import com.example.comicdav.data.ComicCacheKey
import com.example.comicdav.data.ComicDownloadCache
import com.example.comicdav.nativebridge.ComicEngine
import com.example.comicdav.nativebridge.ComicReaderSession
import com.example.comicdav.nativebridge.RangeProviderRegistry
import com.example.comicdav.network.WebDavClient
import com.example.comicdav.network.WebDavRangeProvider
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

typealias RemoteComicSessionFactory = (path: String) -> ComicReaderSession
typealias RemoteRangeComicSessionFactory = (fileId: Long, size: Long, cacheDir: File) -> ComicReaderSession

interface ReadingProgressGateway {
    suspend fun savePage(comicKey: String, pageIndex: Int)
    suspend fun loadPage(comicKey: String): Int
}

data class OpenComicResult(
    val comicKey: String,
    val localFile: File,
    val session: ComicReaderSession,
    val initialPage: Int,
)

class OpenComicUseCase(
    private val accountId: String,
    private val cache: ComicDownloadCache,
    private val progressStore: ReadingProgressGateway,
    private val openSession: RemoteComicSessionFactory = { path -> ComicEngine().openLocal(path) },
    private val openRemoteSession: RemoteRangeComicSessionFactory = { fileId, size, cacheDir ->
        ComicEngine().openRemote(fileId, size, cacheDir)
    },
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    suspend fun open(
        client: WebDavClient,
        remotePath: String,
        onProgress: (downloadedBytes: Long, totalBytes: Long) -> Unit = { _, _ -> },
    ): OpenComicResult {
        val info = client.head(remotePath)
        val key = ComicCacheKey.fromRemote(
            accountId = accountId,
            remotePath = remotePath,
            size = info.size,
            etag = info.etag,
            lastModified = info.lastModified,
        )
        try {
            return openRemote(client, remotePath, info.size, key)
        } catch (error: CancellationException) {
            throw error
        } catch (_: Throwable) {
            // Fall back to the whole-file cache when Range open is unsupported or fails.
        }
        return openWholeFile(client, remotePath, info.size, key, onProgress)
    }

    private suspend fun openRemote(
        client: WebDavClient,
        remotePath: String,
        size: Long,
        key: ComicCacheKey,
    ): OpenComicResult {
        cache.cacheDir.mkdirs()
        val fileId = RangeProviderRegistry.register(WebDavRangeProvider(client, remotePath, size))
        return try {
            val session = withContext(ioDispatcher) {
                openRemoteSession(fileId, size, cache.cacheDir)
            }
            val initialPage = progressStore.loadPage(key.value).coerceIn(0, (session.pageCount - 1).coerceAtLeast(0))
            OpenComicResult(
                comicKey = key.value,
                localFile = cache.cacheDir.resolve("${key.value}.remote"),
                session = session,
                initialPage = initialPage,
            )
        } catch (error: Throwable) {
            RangeProviderRegistry.unregister(fileId)
            throw error
        }
    }

    private suspend fun openWholeFile(
        client: WebDavClient,
        remotePath: String,
        size: Long,
        key: ComicCacheKey,
        onProgress: (downloadedBytes: Long, totalBytes: Long) -> Unit,
    ): OpenComicResult {
        val localFile = cache.download(
            client = client,
            remotePath = remotePath,
            key = key,
            expectedSize = size,
            onProgress = onProgress,
        )
        val session = withContext(ioDispatcher) {
            openSession(localFile.absolutePath)
        }
        val initialPage = progressStore.loadPage(key.value).coerceIn(0, (session.pageCount - 1).coerceAtLeast(0))
        return OpenComicResult(
            comicKey = key.value,
            localFile = localFile,
            session = session,
            initialPage = initialPage,
        )
    }
}
