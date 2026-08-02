package org.mubox.reader.feature.videolibrary

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import org.mubox.reader.core.crypto.sha256Hex
import org.mubox.reader.core.io.FileLruPruner
import org.mubox.reader.core.model.media.VIDEO_THUMBNAIL_CACHE_SUBDIRECTORY
import org.mubox.reader.core.model.media.videoThumbnailFile
import org.mubox.reader.core.model.media.videoThumbnailFileNameForStableKey
import java.io.File
import java.io.FileOutputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

sealed class VideoThumbnailSource {
    data class ContentUri(val context: Context, val uri: Uri) : VideoThumbnailSource()
    data class FilePath(val file: File) : VideoThumbnailSource()
    data class Url(val url: String, val headers: Map<String, String> = emptyMap()) : VideoThumbnailSource()
}

fun interface VideoThumbnailFrameProvider {
    fun frameFor(source: VideoThumbnailSource): Bitmap?
}

class VideoThumbnailExtractor(
    private val cacheDir: File,
    private val frameProvider: VideoThumbnailFrameProvider = AndroidVideoThumbnailFrameProvider(),
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val maxCacheBytes: Long? = null,
) {
    private val extractionLocks = Array(EXTRACTION_LOCK_STRIPE_COUNT) { Mutex() }
    private val protectedCacheFilesLock = Any()
    private val protectedCacheFiles = mutableSetOf<File>()
    private val retainedCacheFiles = mutableSetOf<File>()

    init {
        require(maxCacheBytes == null || maxCacheBytes > 0L)
    }

    /** Keeps library and history artwork out of automatic LRU pruning. */
    fun updateRetainedThumbnails(
        stableKeys: Set<String>,
        explicitFiles: Set<File> = emptySet(),
    ) {
        synchronized(protectedCacheFilesLock) {
            retainedCacheFiles.clear()
            stableKeys.mapTo(retainedCacheFiles) { stableKey ->
                videoThumbnailFile(cacheDir, stableKey)
            }
            explicitFiles.mapTo(retainedCacheFiles) { file -> file.absoluteFile }
        }
    }

    suspend fun extractFromContentUri(
        context: Context,
        uri: Uri,
        stableKey: String = uri.toString().sha256Hex(),
        forceRefresh: Boolean = false,
    ): String? = extractThumbnail(
        source = VideoThumbnailSource.ContentUri(context, uri),
        stableKey = stableKey,
        forceRefresh = forceRefresh,
    )

    suspend fun extractFromFile(
        file: File,
        stableKey: String = file.absolutePath.sha256Hex(),
        forceRefresh: Boolean = false,
    ): String? = extractThumbnail(
        source = VideoThumbnailSource.FilePath(file),
        stableKey = stableKey,
        forceRefresh = forceRefresh,
    )

    suspend fun extractFromUrl(
        url: String,
        stableKey: String = url.sha256Hex(),
        headers: Map<String, String> = emptyMap(),
        forceRefresh: Boolean = false,
    ): String? = extractThumbnail(
        source = VideoThumbnailSource.Url(url, headers),
        stableKey = stableKey,
        forceRefresh = forceRefresh,
    )

    suspend fun cachedThumbnailPath(stableKey: String): String? =
        withContext(ioDispatcher) {
            try {
                extractionLock(stableKey).withLock {
                    val finalFile = videoThumbnailFile(cacheDir, stableKey)
                    val thumbnailDir = requireNotNull(finalFile.parentFile)
                    val tmpFile = thumbnailDir.resolve("${finalFile.name}.tmp")
                    withProtectedCacheFiles(finalFile, tmpFile) {
                        cachedThumbnailPathLocked(stableKey)
                    }
                }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Throwable) {
                null
            }
        }

    suspend fun extractThumbnail(
        source: VideoThumbnailSource,
        stableKey: String,
        forceRefresh: Boolean = false,
    ): String? = withContext(ioDispatcher) {
        extractionLock(stableKey).withLock {
            extractThumbnailLocked(
                source = source,
                stableKey = stableKey,
                forceRefresh = forceRefresh,
            )
        }
    }

    private fun extractThumbnailLocked(
        source: VideoThumbnailSource,
        stableKey: String,
        forceRefresh: Boolean,
    ): String? {
        val finalFile = videoThumbnailFile(cacheDir, stableKey)
        val thumbnailDir = requireNotNull(finalFile.parentFile)
        val tmpFile = thumbnailDir.resolve("${finalFile.name}.tmp")
        return withProtectedCacheFiles(finalFile, tmpFile) {
            try {
                if (!forceRefresh) {
                    cachedThumbnailPathLocked(stableKey)?.let { return it }
                }
                val frame = frameProvider.frameFor(source) ?: return null
                thumbnailDir.mkdirs()
                tmpFile.delete()
                try {
                    FileOutputStream(tmpFile).use { output ->
                        if (!frame.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, output)) {
                            return null
                        }
                    }
                    if (!tmpFile.isFile || tmpFile.length() <= 0L) {
                        return null
                    }
                    if (finalFile.exists() && !finalFile.delete()) {
                        return null
                    }
                    if (!tmpFile.renameTo(finalFile)) {
                        tmpFile.copyTo(finalFile, overwrite = true)
                        tmpFile.delete()
                    }
                    cachedThumbnailResult(finalFile)
                } finally {
                    tmpFile.delete()
                }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Throwable) {
                null
            }
        }
    }

    private fun cachedThumbnailPathLocked(stableKey: String): String? {
        val sharedFile = videoThumbnailFile(cacheDir, stableKey)
        if (sharedFile.isFile && sharedFile.length() > 0L) {
            return cachedThumbnailResult(sharedFile)
        }
        val legacyBrowserFile = cacheDir
            .resolve(VIDEO_THUMBNAIL_CACHE_SUBDIRECTORY)
            .resolve("browser")
            .resolve(videoThumbnailFileNameForStableKey(stableKey))
            .takeIf { it.isFile && it.length() > 0L }
            ?: return null
        sharedFile.parentFile?.mkdirs()
        if (sharedFile.exists() && !sharedFile.delete()) return null
        if (!legacyBrowserFile.renameTo(sharedFile)) {
            legacyBrowserFile.copyTo(sharedFile, overwrite = true)
            legacyBrowserFile.delete()
        }
        return sharedFile
            .takeIf { it.isFile && it.length() > 0L }
            ?.let(::cachedThumbnailResult)
    }

    private fun cachedThumbnailResult(file: File): String {
        file.setLastModified(System.currentTimeMillis())
        val limit = maxCacheBytes
        if (limit != null) {
            val protectedFiles = synchronized(protectedCacheFilesLock) {
                protectedCacheFiles + retainedCacheFiles
            }
            FileLruPruner.prune(
                root = cacheDir.resolve(VIDEO_THUMBNAIL_CACHE_SUBDIRECTORY),
                maxBytes = limit,
                protectedFiles = protectedFiles,
            )
        }
        return file.absolutePath
    }

    private inline fun <T> withProtectedCacheFiles(
        vararg files: File,
        block: () -> T,
    ): T {
        synchronized(protectedCacheFilesLock) {
            protectedCacheFiles += files
        }
        return try {
            block()
        } finally {
            synchronized(protectedCacheFilesLock) {
                protectedCacheFiles -= files.toSet()
            }
        }
    }

    private fun extractionLock(stableKey: String): Mutex =
        extractionLocks[Math.floorMod(stableKey.hashCode(), extractionLocks.size)]

    private companion object {
        const val JPEG_QUALITY = 85
        const val EXTRACTION_LOCK_STRIPE_COUNT = 32
    }
}

private class AndroidVideoThumbnailFrameProvider : VideoThumbnailFrameProvider {
    override fun frameFor(source: VideoThumbnailSource): Bitmap? {
        val retriever = MediaMetadataRetriever()
        try {
            when (source) {
                is VideoThumbnailSource.ContentUri -> retriever.setDataSource(source.context, source.uri)
                is VideoThumbnailSource.FilePath -> retriever.setDataSource(source.file.absolutePath)
                is VideoThumbnailSource.Url -> {
                    if (source.headers.isEmpty()) {
                        retriever.setDataSource(source.url)
                    } else {
                        retriever.setDataSource(source.url, source.headers)
                    }
                }
            }
            return retriever.embeddedCoverArt()
                ?: retriever.getFrameAtTime(1_000_000L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                ?: retriever.frameAtTime
        } finally {
            retriever.release()
        }
    }
}

private fun MediaMetadataRetriever.embeddedCoverArt(): Bitmap? {
    val picture = runCatching { embeddedPicture }.getOrNull() ?: return null
    return runCatching { BitmapFactory.decodeByteArray(picture, 0, picture.size) }.getOrNull()
}
