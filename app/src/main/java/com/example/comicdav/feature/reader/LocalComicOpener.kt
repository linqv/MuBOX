package com.example.comicdav.feature.reader

import android.content.Context
import android.net.Uri
import com.example.comicdav.data.LocalArchiveFormat
import com.example.comicdav.data.localArchiveFormatForFileName
import com.example.comicdav.nativebridge.ComicEngine
import com.example.comicdav.nativebridge.ComicReaderSession

typealias OpenLocalFdSessionFactory = (
    fd: Int,
    size: Long,
    format: LocalArchiveFormat,
) -> ComicReaderSession

class LocalComicOpener(
    private val context: Context,
    private val openSession: OpenLocalFdSessionFactory = { fd, size, format ->
        ComicEngine().openLocalFd(fd, size, format.nativeName)
    },
) {
    fun open(uri: Uri, fileName: String): ComicReaderSession {
        val format = localArchiveFormatForFileName(fileName)
            ?: error("暂不支持这个本地漫画格式")
        val descriptor = context.contentResolver.openFileDescriptor(uri, "r")
            ?: error("无法读取所选文件")
        val size = descriptor.statSize.takeIf { it > 0L } ?: 0L
        val fd = descriptor.detachFd()
        return openSession(fd, size, format)
    }
}
