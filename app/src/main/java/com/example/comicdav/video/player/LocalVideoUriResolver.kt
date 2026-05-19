package com.example.comicdav.video.player

import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import java.io.File

class LocalVideoUriResolver(
    private val context: Context,
) {
    fun resolve(uriText: String): String {
        val uri = Uri.parse(uriText)
        if (uri.scheme != "content") return uriText

        return resolveMediaStoreDataPath(uri)
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

    private fun resolveFileDescriptorUri(uri: Uri, uriText: String): String =
        runCatching {
            val descriptor = context.contentResolver.openFileDescriptor(uri, "r")
                ?: throw IllegalStateException("内容提供方没有返回可读取的文件描述符")

            // detachFd transfers ownership to mpv. Do not close the detached fd here.
            "fd://${descriptor.detachFd()}"
        }.getOrElse { error ->
            throw IllegalStateException("无法读取本地视频：$uriText", error)
        }
}
