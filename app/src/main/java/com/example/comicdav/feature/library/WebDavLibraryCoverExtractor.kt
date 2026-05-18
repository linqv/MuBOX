package com.example.comicdav.feature.library

import com.example.comicdav.data.ComicCacheKey
import com.example.comicdav.feature.reader.RemoteRangeComicSessionFactory
import com.example.comicdav.nativebridge.ComicEngine
import com.example.comicdav.nativebridge.ComicReaderSession
import com.example.comicdav.nativebridge.RangeProviderRegistry
import com.example.comicdav.network.RemoteFileInfo
import com.example.comicdav.network.WebDavClient
import com.example.comicdav.network.WebDavRangeProvider
import java.io.File
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class WebDavLibraryCoverExtractor(
    private val appCacheDir: File,
    private val remoteCacheDir: File,
    private val openRemoteSession: RemoteRangeComicSessionFactory = { fileId, size, cacheDir, comicKey, validator, webDavPrefetchPageCount ->
        ComicEngine().openRemote(fileId, size, cacheDir, comicKey, validator, webDavPrefetchPageCount)
    },
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    suspend fun extractFirstPageCover(
        client: WebDavClient,
        accountId: String,
        remotePath: String,
        knownInfo: RemoteFileInfo? = null,
    ): String? = withContext(ioDispatcher) {
        val info = knownInfo ?: client.head(remotePath)
        val cacheKey = ComicCacheKey.fromRemote(
            accountId = accountId,
            remotePath = remotePath,
            size = info.size,
            etag = info.etag,
            lastModified = info.lastModified,
        )
        val coverFile = coverFile(cacheKey)
        if (coverFile.isFile && coverFile.length() > 0L) {
            coverFile.setLastModified(System.currentTimeMillis())
            return@withContext coverFile.absolutePath
        }

        val fileId = RangeProviderRegistry.register(WebDavRangeProvider(client, remotePath, info.size))
        var session: ComicReaderSession? = null
        val tmpFile = File(coverFile.parentFile, "${coverFile.name}.tmp")
        try {
            session = openRemoteSession(
                fileId,
                info.size,
                remoteCacheDir,
                cacheKey.value,
                info.validator(),
                0,
            )
            if (session.pageCount <= 0) {
                return@withContext null
            }

            coverFile.parentFile?.mkdirs()
            tmpFile.delete()
            val loadedFile = session.loadPageToFile(0, tmpFile)
            if (!loadedFile.isFile || loadedFile.length() <= 0L) {
                tmpFile.delete()
                return@withContext null
            }
            if (coverFile.exists()) {
                coverFile.delete()
            }
            if (!loadedFile.renameTo(coverFile)) {
                loadedFile.copyTo(coverFile, overwrite = true)
                loadedFile.delete()
            }
            coverFile.setLastModified(System.currentTimeMillis())
            coverFile.absolutePath
        } finally {
            runCatching { session?.close() }
            RangeProviderRegistry.unregister(fileId)
            tmpFile.delete()
        }
    }

    private fun coverFile(cacheKey: ComicCacheKey): File =
        File(appCacheDir, "library-covers/${cacheKey.value}.img")

    private fun RemoteFileInfo.validator(): String =
        etag ?: lastModified?.toString() ?: size.toString()
}
