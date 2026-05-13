package com.example.comicdav.feature.reader

import com.example.comicdav.data.ComicCacheKey
import com.example.comicdav.data.ComicDownloadCache
import com.example.comicdav.nativebridge.ComicEngine
import com.example.comicdav.nativebridge.ComicReaderSession
import com.example.comicdav.network.WebDavClient
import java.io.File

typealias RemoteComicSessionFactory = (path: String) -> ComicReaderSession

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
        val localFile = cache.download(
            client = client,
            remotePath = remotePath,
            key = key,
            expectedSize = info.size,
            onProgress = onProgress,
        )
        val session = openSession(localFile.absolutePath)
        val initialPage = progressStore.loadPage(key.value).coerceIn(0, (session.pageCount - 1).coerceAtLeast(0))
        return OpenComicResult(
            comicKey = key.value,
            localFile = localFile,
            session = session,
            initialPage = initialPage,
        )
    }
}
