package org.mubox.reader.feature.reader

import android.content.Context
import android.net.Uri
import androidx.annotation.WorkerThread
import org.mubox.reader.core.model.media.isSupportedLocalComicFileName
import org.mubox.reader.core.ports.ComicReaderSession

typealias OpenLocalFdSessionFactory = (
    fd: Int,
    size: Long,
) -> ComicReaderSession

class LocalComicOpener(
    private val context: Context,
    private val openSession: OpenLocalFdSessionFactory,
) {
    @WorkerThread
    fun open(uri: Uri, fileName: String): ComicReaderSession {
        check(isSupportedLocalComicFileName(fileName)) { "暂不支持这个本地阅读格式" }
        val descriptor = context.contentResolver.openFileDescriptor(uri, "r")
            ?: error("无法读取所选文件")
        val size = descriptor.statSize.takeIf { it > 0L } ?: 0L
        val fd = descriptor.detachFd()
        return openSession(fd, size)
    }
}
