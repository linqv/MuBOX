package org.mubox.reader.video.player

import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import java.io.FileOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

internal class ManagedPlaybackUri(
    val uri: String,
    private val closeUnusedResource: () -> Unit = {},
) {
    private val consumed = AtomicBoolean(false)
    private val closed = AtomicBoolean(false)

    fun markConsumed() {
        consumed.set(true)
    }

    fun closeIfUnused() {
        if (!consumed.get() && closed.compareAndSet(false, true)) {
            closeUnusedResource()
        }
    }
}

internal class LocalVideoUriResolver(
    private val context: Context,
) {
    fun resolve(uriText: String): String =
        resolveForPlayback(uriText).also { it.markConsumed() }.uri

    fun resolveForPlayback(uriText: String): ManagedPlaybackUri {
        val uri = Uri.parse(uriText)
        if (uri.scheme != "content") return ManagedPlaybackUri(uriText)

        return resolveMediaStoreDataPath(uri)?.let(::ManagedPlaybackUri)
            ?: resolveFileDescriptorUri(uri, uriText)
    }

    fun resolveSubtitle(uriText: String, displayName: String): String =
        resolveSubtitleForPlayback(uriText, displayName).also { it.markConsumed() }.uri

    fun resolveSubtitleForPlayback(uriText: String, displayName: String): ManagedPlaybackUri {
        val uri = Uri.parse(uriText)
        if (uri.scheme != "content") return ManagedPlaybackUri(uriText)

        return resolveMediaStoreDataPath(uri)?.let(::ManagedPlaybackUri)
            ?: copyContentUriToNamedCacheFile(uri, uriText, displayName)?.let(::ManagedPlaybackUri)
            ?: resolveFileDescriptorUri(uri, uriText)
    }

    private fun resolveMediaStoreDataPath(uri: Uri): String? =
        runCatching {
            context.contentResolver
                .query(uri, arrayOf(MediaStore.MediaColumns.DATA), null, null, null)
                ?.use { cursor ->
                    if (!cursor.moveToFirst()) return@use null
                    val columnIndex = cursor.getColumnIndex(MediaStore.MediaColumns.DATA)
                    if (columnIndex == -1) return@use null

                    cursor.getString(columnIndex)
                        ?.takeIf { it.isNotBlank() }
                        ?.takeIf { path ->
                            val file = File(path)
                            file.exists() && file.canRead()
                        }
                }
        }.getOrNull()

    private fun copyContentUriToNamedCacheFile(uri: Uri, uriText: String, displayName: String): String? =
        runCatching {
            val fileName = safeCacheFileName(uriText = uriText, displayName = displayName)
            val directory = File(context.cacheDir, "video-subtitles").apply { mkdirs() }
            val target = File(directory, fileName)
            context.contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(target).use { output ->
                    input.copyTo(output)
                }
            } ?: return@runCatching null
            target.absolutePath
        }.getOrNull()

    private fun resolveFileDescriptorUri(uri: Uri, uriText: String): ManagedPlaybackUri =
        runCatching {
            context.contentResolver.openFileDescriptor(uri, "r")
                ?.use { descriptor ->
                    // detachFd transfers ownership to mpv before the descriptor wrapper is closed.
                    val detachedFd = descriptor.detachFd()
                    ManagedPlaybackUri("fd://$detachedFd") {
                        ParcelFileDescriptor.adoptFd(detachedFd).close()
                    }
                }
                ?: throw IllegalStateException("内容提供方没有返回可读取的文件描述符")
        }.getOrElse { error ->
            throw IllegalStateException("无法读取本地视频：$uriText", error)
        }

    private fun safeCacheFileName(uriText: String, displayName: String): String {
        val rawDisplayName = displayName
            .takeIf { it.isNotBlank() }
            ?.substringAfterLast('/')
            ?.substringAfterLast('\\')
            .orEmpty()
        val rawBaseName = rawDisplayName.substringBeforeLast('.', missingDelimiterValue = rawDisplayName)
        val safeBaseName = rawBaseName.safeCacheNamePart().takeIf { it.isNotBlank() } ?: "subtitle"
        val safeExtension = rawDisplayName
            .substringAfterLast('.', missingDelimiterValue = "")
            .safeCacheExtension()
        val safeDisplayName = if (safeExtension.isBlank()) {
            safeBaseName
        } else {
            "$safeBaseName.$safeExtension"
        }
        return "${uriText.sha256Hex().take(16)}-$safeDisplayName"
    }

    private fun String.safeCacheNamePart(): String =
        replace(UnsafeFileNameChars, "_")
            .trim('_', '.', ' ')

    private fun String.safeCacheExtension(): String =
        replace(UnsafeExtensionChars, "")
            .trim('.', ' ')

    private fun String.sha256Hex(): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(toByteArray(Charsets.UTF_8))
        return digest.joinToString(separator = "") { byte -> "%02x".format(Locale.ROOT, byte) }
    }

    private companion object {
        val UnsafeFileNameChars = Regex("[^A-Za-z0-9._-]+")
        val UnsafeExtensionChars = Regex("[^A-Za-z0-9]+")
    }
}
