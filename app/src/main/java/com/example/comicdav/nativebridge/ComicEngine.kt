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

    fun openLocalFd(fd: Int, size: Long, format: String): ComicReaderSession {
        val handle = native.openLocalFd(fd, size, format)
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
            rangeProviderFileId = fileId,
            onClose = { RangeProviderRegistry.unregister(fileId) },
        )
    }

    private fun openChecked(
        handle: Long,
        rangeProviderFileId: Long? = null,
        onClose: () -> Unit = {},
    ) : ComicReaderSession {
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

        return ComicSession(native, handle, pageCount, rangeProviderFileId, onClose)
    }

    private fun nativeException(): ComicNativeException {
        return ComicNativeException(native.lastErrorMessage().ifBlank { "Native comic operation failed" })
    }
}

interface ComicReaderSession : Closeable {
    val pageCount: Int
    val forwardPrefetchPageCount: Int
        get() = 4
    val backwardPrefetchPageCount: Int
        get() = 1

    fun loadPageToFile(pageIndex: Int, outputFile: File): File
    fun updateViewport(pageIndex: Int, networkClass: Int) = Unit
    fun diagnostics(): String = ""
    fun plannedRanges(pageIndex: Int, networkClass: Int): List<PlannedRemoteRange> = emptyList()
    fun prefetchRange(start: Long, endInclusive: Long): Boolean = false
    fun prefetchRange(
        start: Long,
        endInclusive: Long,
        priority: Int,
        protectedRanges: List<LongRange>,
    ): Boolean = prefetchRange(start, endInclusive)
}

data class PlannedRemoteRange(
    val start: Long,
    val endInclusive: Long,
    val pages: List<Int>,
    val priority: Int,
)

class ComicSession internal constructor(
    private val native: ComicNativeFacade,
    private val handle: Long,
    override val pageCount: Int,
    private val rangeProviderFileId: Long? = null,
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

    override fun plannedRanges(pageIndex: Int, networkClass: Int): List<PlannedRemoteRange> {
        val encoded = native.plannedRanges(handle, pageIndex, networkClass)
        return decodePlannedRanges(encoded)
    }

    override fun prefetchRange(start: Long, endInclusive: Long): Boolean {
        val fileId = rangeProviderFileId ?: return false
        return RangeProviderRegistry.prefetchRange(fileId, start, endInclusive)
    }

    override fun prefetchRange(
        start: Long,
        endInclusive: Long,
        priority: Int,
        protectedRanges: List<LongRange>,
    ): Boolean {
        val fileId = rangeProviderFileId ?: return false
        return RangeProviderRegistry.prefetchRange(fileId, start, endInclusive, priority, protectedRanges)
    }

    override fun close() {
        if (!isClosed) {
            isClosed = true
            native.close(handle)
            onClose()
        }
    }
}

internal fun decodePlannedRanges(encoded: String): List<PlannedRemoteRange> {
    val trimmed = encoded.trim()
    if (trimmed.isEmpty() || trimmed == "v1") return emptyList()
    require(trimmed.startsWith("v1;")) { "Unsupported planned range format" }
    return trimmed
        .removePrefix("v1;")
        .split(';')
        .filter { it.isNotBlank() }
        .map { entry ->
            val fields = entry.split(',')
            require(fields.size == 4) { "Malformed planned range entry" }
            PlannedRemoteRange(
                start = fields[0].toLong(),
                endInclusive = fields[1].toLong(),
                priority = fields[2].toInt(),
                pages = fields[3]
                    .split('|')
                    .filter { it.isNotBlank() }
                    .map { it.toInt() },
            )
        }
}

class ComicNativeException(message: String) : RuntimeException(message)
