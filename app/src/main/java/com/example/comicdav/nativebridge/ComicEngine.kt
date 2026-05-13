package com.example.comicdav.nativebridge

import java.io.Closeable
import java.io.File

class ComicEngine(
    private val native: ComicNativeFacade = ComicNative,
) {
    fun openLocal(path: String): ComicReaderSession {
        val handle = native.openLocal(path)
        if (handle == 0L) {
            throw nativeException()
        }

        val pageCount = native.pageCount(handle)
        if (pageCount < 0) {
            native.close(handle)
            throw nativeException()
        }

        return ComicSession(native, handle, pageCount)
    }

    private fun nativeException(): ComicNativeException {
        return ComicNativeException(native.lastErrorMessage().ifBlank { "Native comic operation failed" })
    }
}

interface ComicReaderSession : Closeable {
    val pageCount: Int
    fun loadPageToFile(pageIndex: Int, outputFile: File): File
}

class ComicSession internal constructor(
    private val native: ComicNativeFacade,
    private val handle: Long,
    override val pageCount: Int,
) : ComicReaderSession {
    private var isClosed = false

    override fun loadPageToFile(pageIndex: Int, outputFile: File): File {
        val result = native.loadPageToFile(handle, pageIndex, outputFile.absolutePath)
        if (result < 0) {
            throw ComicNativeException(native.lastErrorMessage().ifBlank { "Failed to load page" })
        }
        return outputFile
    }

    override fun close() {
        if (!isClosed) {
            isClosed = true
            native.close(handle)
        }
    }
}

class ComicNativeException(message: String) : RuntimeException(message)
