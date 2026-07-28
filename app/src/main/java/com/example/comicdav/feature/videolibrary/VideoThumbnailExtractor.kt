package com.example.comicdav.feature.videolibrary

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
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
) {
    suspend fun extractFromContentUri(
        context: Context,
        uri: Uri,
        stableKey: String = uri.toString().sha256Hex(),
    ): String? = extractThumbnail(
        source = VideoThumbnailSource.ContentUri(context, uri),
        stableKey = stableKey,
    )

    suspend fun extractFromFile(
        file: File,
        stableKey: String = file.absolutePath.sha256Hex(),
    ): String? = extractThumbnail(
        source = VideoThumbnailSource.FilePath(file),
        stableKey = stableKey,
    )

    suspend fun extractFromUrl(
        url: String,
        stableKey: String = url.sha256Hex(),
        headers: Map<String, String> = emptyMap(),
    ): String? = extractThumbnail(
        source = VideoThumbnailSource.Url(url, headers),
        stableKey = stableKey,
    )

    suspend fun extractThumbnail(
        source: VideoThumbnailSource,
        stableKey: String,
    ): String? = withContext(ioDispatcher) {
        try {
            val frame = frameProvider.frameFor(source) ?: return@withContext null
            val thumbnailDir = cacheDir.resolve(cacheSubdirectory)
            thumbnailDir.mkdirs()
            val finalFile = thumbnailDir.resolve(thumbnailFileNameForStableKey(stableKey))
            val tmpFile = thumbnailDir.resolve("${finalFile.name}.tmp")
            tmpFile.delete()
            try {
                FileOutputStream(tmpFile).use { output ->
                    if (!frame.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, output)) {
                        return@withContext null
                    }
                }
                if (!tmpFile.isFile || tmpFile.length() <= 0L) {
                    return@withContext null
                }
                if (finalFile.exists() && !finalFile.delete()) {
                    return@withContext null
                }
                if (!tmpFile.renameTo(finalFile)) {
                    tmpFile.copyTo(finalFile, overwrite = true)
                    tmpFile.delete()
                }
                finalFile.absolutePath
            } finally {
                tmpFile.delete()
            }
        } catch (error: CancellationException) {
            throw error
        } catch (_: Throwable) {
            null
        }
    }

    private companion object {
        const val JPEG_QUALITY = 85
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
