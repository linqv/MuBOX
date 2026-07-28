package com.example.comicdav.feature.videolibrary

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import com.example.comicdav.core.io.FileLruPruner
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
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
    private val cacheSubdirectory: String = "video-library-thumbnails",
    private val maxCacheBytes: Long? = null,
) {
    private val extractionLocks = Array(EXTRACTION_LOCK_STRIPE_COUNT) { Mutex() }
    private val protectedCacheFilesLock = Any()
    private val protectedCacheFiles = mutableSetOf<File>()

    init {
        require(maxCacheBytes == null || maxCacheBytes > 0L)
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
                    val thumbnailDir = cacheDir.resolve(cacheSubdirectory)
                    val finalFile = thumbnailDir.resolve(thumbnailFileNameForStableKey(stableKey))
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
        val thumbnailDir = cacheDir.resolve(cacheSubdirectory)
        val finalFile = thumbnailDir.resolve(thumbnailFileNameForStableKey(stableKey))
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

    private fun cachedThumbnailPathLocked(stableKey: String): String? =
        cacheDir
            .resolve(cacheSubdirectory)
            .resolve(thumbnailFileNameForStableKey(stableKey))
            .takeIf { it.isFile && it.length() > 0L }
            ?.let(::cachedThumbnailResult)

    private fun cachedThumbnailResult(file: File): String {
        file.setLastModified(System.currentTimeMillis())
        val limit = maxCacheBytes
        if (limit != null) {
            val protectedFiles = synchronized(protectedCacheFilesLock) {
                protectedCacheFiles.toSet()
            }
            FileLruPruner.prune(
                root = cacheDir.resolve(cacheSubdirectory),
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

internal fun thumbnailFileNameForStableKey(stableKey: String): String {
    val readablePrefix = stableKey.sanitizeThumbnailKey().take(48)
    val hash = stableKey.sha256Hex()
    return if (readablePrefix.isBlank()) {
        "$hash.jpg"
    } else {
        "$readablePrefix-$hash.jpg"
    }
}

private fun String.sanitizeThumbnailKey(): String {
    val sanitized = replace(Regex("[^A-Za-z0-9._-]"), "_")
        .trim('_', '.', '-')
    return sanitized.ifBlank { sha256Hex() }
}

private fun String.sha256Hex(): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(toByteArray(Charsets.UTF_8))
    return digest.joinToString(separator = "") { byte -> "%02x".format(byte) }
}
