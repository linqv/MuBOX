package com.example.comicdav.nativebridge

import androidx.annotation.WorkerThread
import com.example.comicdav.feature.reader.ReaderDiagnosticLog
import com.example.comicdav.feature.reader.ReaderLogCategory
import java.io.Closeable
import java.io.File

class ComicEngine(
    private val native: ComicNativeFacade = ComicNative,
    private val logDiagnostic: (() -> String) -> Unit = { event ->
        ReaderDiagnosticLog.summary(ReaderLogCategory.LOCAL_FILE, event)
    },
    private val elapsedRealtimeMs: () -> Long = { System.nanoTime() / 1_000_000L },
) {
    @WorkerThread
    fun openLocal(path: String, avifImagesEnabled: Boolean = false): ComicReaderSession {
        val handle = native.openLocal(path, avifImagesEnabled)
        return openChecked(handle)
    }

    @WorkerThread
    fun openLocalFd(
        fd: Int,
        size: Long,
        format: String,
        avifImagesEnabled: Boolean = false,
    ): ComicReaderSession {
        val nativeOpenStartMs = elapsedRealtimeMs()
        val handle = native.openLocalFd(fd, size, format, avifImagesEnabled)
        val nativeOpenEndMs = elapsedRealtimeMs()
        return openChecked(
            handle = handle,
            nativeOpenDiagnostics = NativeOpenDiagnostics(
                format = format,
                sizeBytes = size,
                nativeOpenMs = nativeOpenEndMs - nativeOpenStartMs,
                pageCountStartMs = nativeOpenEndMs,
            ),
        )
    }

    // Note: openRemote registers a RangeProvider whose readRange callback is invoked
    // synchronously by Rust. Inside that callback, Java may runBlocking to await OkHttp I/O.
    @WorkerThread
    fun openRemote(
        fileId: Long,
        size: Long,
        cacheDir: File,
        comicKey: String,
        validator: String,
        avifImagesEnabled: Boolean = false,
        webDavPrefetchPageCount: Int = 4,
    ): ComicReaderSession {
        val handle = native.openRemote(
            fileId,
            size,
            cacheDir.absolutePath,
            comicKey,
            validator,
            avifImagesEnabled,
        )
        return openChecked(
            handle = handle,
            rangeProviderFileId = fileId,
            onClose = { RangeProviderRegistry.unregister(fileId) },
            forwardPrefetchPageCount = webDavPrefetchPageCount,
        )
    }

    private fun openChecked(
        handle: Long,
        rangeProviderFileId: Long? = null,
        onClose: () -> Unit = {},
        nativeOpenDiagnostics: NativeOpenDiagnostics? = null,
        forwardPrefetchPageCount: Int = 4,
    ) : ComicReaderSession {
        if (handle == 0L) {
            onClose()
            throw nativeException()
        }

        val pageCount = native.pageCount(handle)
        val pageCountMs = nativeOpenDiagnostics?.let {
            elapsedRealtimeMs() - it.pageCountStartMs
        }
        if (pageCount < 0) {
            native.close(handle)
            onClose()
            throw nativeException()
        }

        if (nativeOpenDiagnostics != null) {
            logDiagnostic {
                "native_open_local_fd_done format=${nativeOpenDiagnostics.format} " +
                    "sizeBytes=${nativeOpenDiagnostics.sizeBytes} " +
                    "nativeOpenMs=${nativeOpenDiagnostics.nativeOpenMs} " +
                    "pageCountMs=$pageCountMs " +
                    "pageCount=$pageCount"
            }
        }

        return ComicSession(
            native = native,
            handle = handle,
            pageCount = pageCount,
            rangeProviderFileId = rangeProviderFileId,
            onClose = onClose,
            forwardPrefetchPageCount = forwardPrefetchPageCount,
        )
    }

    private fun nativeException(): ComicNativeException {
        return ComicNativeException(native.lastErrorMessage().ifBlank { "Native comic operation failed" })
    }

    private data class NativeOpenDiagnostics(
        val format: String,
        val sizeBytes: Long,
        val nativeOpenMs: Long,
        val pageCountStartMs: Long,
    )
}

interface ComicReaderSession : Closeable {
    val pageCount: Int
    val forwardPrefetchPageCount: Int
        get() = 4
    val backwardPrefetchPageCount: Int
        get() = 1
    val advancePrefetchOnPageDemand: Boolean
        get() = false

    @WorkerThread
    fun loadPageToFile(pageIndex: Int, outputFile: File): File
    @WorkerThread
    fun updateViewport(pageIndex: Int, networkClass: Int) = Unit
    fun diagnostics(): String = ""
    @WorkerThread
    fun plannedRanges(pageIndex: Int, networkClass: Int): List<PlannedRemoteRange> = emptyList()
    @WorkerThread
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
    override val forwardPrefetchPageCount: Int = 4,
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
        val result = native.updateViewport(handle, pageIndex, networkClass, forwardPrefetchPageCount)
        if (result < 0) {
            throw ComicNativeException(native.lastErrorMessage().ifBlank { "Failed to update viewport" })
        }
    }

    override fun diagnostics(): String = native.diagnostics(handle)

    override fun plannedRanges(pageIndex: Int, networkClass: Int): List<PlannedRemoteRange> {
        val encoded = native.plannedRanges(handle, pageIndex, networkClass, forwardPrefetchPageCount)
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
