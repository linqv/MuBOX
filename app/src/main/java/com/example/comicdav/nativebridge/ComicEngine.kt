package com.example.comicdav.nativebridge

import java.io.Closeable
import java.io.File

class ComicEngine(
    private val native: ComicNativeFacade = ComicNative,
) {
    fun openLocal(path: String): ComicReaderSession {
        val handle = native.openLocal(path)
        return openChecked(handle)
    }

    fun openRemote(
        fileId: Long,
        size: Long,
        cacheDir: File,
        comicKey: String,
        validator: String,
    ): ComicReaderSession {
        val handle = native.openRemote(fileId, size, cacheDir.absolutePath, comicKey, validator)
        return openChecked(
            handle = handle,
            onClose = { RangeProviderRegistry.unregister(fileId) },
        )
    }

    private fun openChecked(handle: Long, onClose: () -> Unit = {}) : ComicReaderSession {
        if (handle == 0L) {
            onClose()
            throw nativeException()
        }

        val pageCount = native.pageCount(handle)
        if (pageCount < 0) {
            native.close(handle)
            onClose()
            throw nativeException()
        }

        return ComicSession(native, handle, pageCount, onClose)
    }

    private fun nativeException(): ComicNativeException {
        return ComicNativeException(native.lastErrorMessage().ifBlank { "Native comic operation failed" })
    }
}

interface ComicReaderSession : Closeable {
    val pageCount: Int
    fun loadPageToFile(pageIndex: Int, outputFile: File): File
    fun updateViewport(pageIndex: Int, networkClass: Int) = Unit
    fun diagnostics(): String = ""
}

class ComicSession internal constructor(
    private val native: ComicNativeFacade,
    private val handle: Long,
    override val pageCount: Int,
    private val onClose: () -> Unit = {},
) : ComicReaderSession {
    private var isClosed = false

    override fun loadPageToFile(pageIndex: Int, outputFile: File): File {
        val result = native.loadPageToFile(handle, pageIndex, outputFile.absolutePath)
        if (result < 0) {
            throw ComicNativeException(native.lastErrorMessage().ifBlank { "Failed to load page" })
        }
        return outputFile
    }

    override fun updateViewport(pageIndex: Int, networkClass: Int) {
        val result = native.updateViewport(handle, pageIndex, networkClass)
        if (result < 0) {
            throw ComicNativeException(native.lastErrorMessage().ifBlank { "Failed to update viewport" })
        }
    }

    override fun diagnostics(): String = native.diagnostics(handle)

    override fun close() {
        if (!isClosed) {
            isClosed = true
            native.close(handle)
            onClose()
        }
    }
}

class ComicNativeException(message: String) : RuntimeException(message)
