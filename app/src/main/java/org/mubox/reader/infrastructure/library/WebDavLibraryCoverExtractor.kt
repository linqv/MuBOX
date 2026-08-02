package org.mubox.reader.infrastructure.library

import org.mubox.reader.core.model.media.readerImageFormatCacheKey
import org.mubox.reader.core.ports.ComicReaderSession
import org.mubox.reader.core.ports.RemoteRangeComicSessionFactory
import org.mubox.reader.data.ComicCacheKey
import org.mubox.reader.nativebridge.ComicEngine
import org.mubox.reader.nativebridge.RangeProviderRegistry
import org.mubox.reader.core.remote.RemoteFileInfo
import org.mubox.reader.core.remote.WebDavClient
import org.mubox.reader.network.WebDavRangeProvider
import java.io.File
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class WebDavLibraryCoverExtractor(
    private val appCacheDir: File,
    private val remoteCacheDir: File,
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
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    suspend fun extractFirstPageCover(
        client: WebDavClient,
        accountId: String,
        remotePath: String,
        avifImagesEnabled: Boolean = false,
        knownInfo: RemoteFileInfo? = null,
    ): String? = withContext(ioDispatcher) { // Dispatched to IO for @WorkerThread native calls
        val info = knownInfo ?: client.head(remotePath)
        val cacheKey = ComicCacheKey.fromRemote(
            accountId = accountId,
            remotePath = remotePath,
            size = info.size,
            etag = info.etag,
            lastModified = info.lastModified,
        )
        val coverFile = coverFile(cacheKey, avifImagesEnabled)
        if (coverFile.isFile && coverFile.length() > 0L) {
            coverFile.setLastModified(System.currentTimeMillis())
            return@withContext coverFile.absolutePath
        }

        val fileId = RangeProviderRegistry.register(
            WebDavRangeProvider(client, remotePath, info.size),
        )
        var session: ComicReaderSession? = null
        val tmpFile = File(coverFile.parentFile, "${coverFile.name}.tmp")
        try {
            session = openRemoteSession(
                fileId,
                info.size,
                remoteCacheDir,
                cacheKey.value,
                info.validator(),
                avifImagesEnabled,
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

    private fun coverFile(cacheKey: ComicCacheKey, avifImagesEnabled: Boolean): File =
        File(appCacheDir, "library-covers/${readerImageFormatCacheKey(cacheKey.value, avifImagesEnabled)}.img")

    private fun RemoteFileInfo.validator(): String =
        etag ?: lastModified?.toString() ?: size.toString()
}
