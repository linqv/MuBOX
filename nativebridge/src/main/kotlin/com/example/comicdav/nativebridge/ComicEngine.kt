package com.example.comicdav.nativebridge

import androidx.annotation.WorkerThread
import com.example.comicdav.core.ports.ComicReaderSession
import com.example.comicdav.core.ports.PlannedRemoteRange
import java.io.File

class ComicEngine(
    private val native: ComicNativeFacade = ComicNative,
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
        val handle = native.openLocalFd(fd, size, format, avifImagesEnabled)
        return openChecked(handle)
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
        forwardPrefetchPageCount: Int = 4,
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
}

class ComicSession internal constructor(
    private val native: ComicNativeFacade,
    private val handle: Long,
    override val pageCount: Int,
    private val rangeProviderFileId: Long? = null,
    private val onClose: () -> Unit = {},
    override val forwardPrefetchPageCount: Int = 4,
) : ComicReaderSession {
    override val advancePrefetchOnPageDemand: Boolean = rangeProviderFileId != null
    private var isClosed = false

    @WorkerThread
    override fun loadPageToFile(pageIndex: Int, outputFile: File): File {
        val result = native.loadPageToFile(handle, pageIndex, outputFile.absolutePath)
        if (result < 0) {
            throw ComicNativeException(native.lastErrorMessage().ifBlank { "Failed to load page" })
        }
        return outputFile
    }

    @WorkerThread
    override fun updateViewport(pageIndex: Int, networkClass: Int) {
        val result = native.updateViewport(handle, pageIndex, networkClass, forwardPrefetchPageCount)
        if (result < 0) {
            throw ComicNativeException(native.lastErrorMessage().ifBlank { "Failed to update viewport" })
        }
    }

    override fun diagnostics(): String =
        when (val result = decodeDiagnostics(native.diagnostics(handle))) {
            is DiagnosticsDecodeResult.Success -> result.payload
            DiagnosticsDecodeResult.NativeError -> throw ComicNativeException(
                native.lastErrorMessage().ifBlank { "Failed to read native diagnostics" },
            )
        }

    @WorkerThread
    override fun plannedRanges(pageIndex: Int, networkClass: Int): List<PlannedRemoteRange> {
        val encoded = native.plannedRanges(handle, pageIndex, networkClass, forwardPrefetchPageCount)
        return when (val result = decodePlannedRanges(encoded)) {
            is PlannedRangesDecodeResult.Success -> result.ranges
            PlannedRangesDecodeResult.NativeError -> throw ComicNativeException(
                native.lastErrorMessage().ifBlank { "Failed to plan native ranges" },
            )
        }
    }

    @WorkerThread
    override fun prefetchRange(start: Long, endInclusive: Long): Boolean {
        val fileId = rangeProviderFileId ?: return false
        return RangeProviderRegistry.prefetchRange(fileId, start, endInclusive)
    }

    @WorkerThread
    override fun prefetchRange(
        start: Long,
        endInclusive: Long,
        priority: Int,
        protectedRanges: List<LongRange>,
    ): Boolean {
        val fileId = rangeProviderFileId ?: return false
        return RangeProviderRegistry.prefetchRange(fileId, start, endInclusive, priority, protectedRanges)
    }

    override fun cancelPrefetches() {
        val fileId = rangeProviderFileId ?: return
        RangeProviderRegistry.cancelPrefetches(fileId)
    }

    override fun close() {
        if (!isClosed) {
            isClosed = true
            native.close(handle)
            onClose()
        }
    }
}

internal sealed interface PlannedRangesDecodeResult {
    data class Success(val ranges: List<PlannedRemoteRange>) : PlannedRangesDecodeResult

    data object NativeError : PlannedRangesDecodeResult
}

internal sealed interface DiagnosticsDecodeResult {
    data class Success(val payload: String) : DiagnosticsDecodeResult

    data object NativeError : DiagnosticsDecodeResult
}

internal fun decodeDiagnostics(encoded: String): DiagnosticsDecodeResult =
    when {
        encoded == "v2;error" -> DiagnosticsDecodeResult.NativeError
        encoded == "v2;ok" -> DiagnosticsDecodeResult.Success("")
        encoded.startsWith("v2;ok;") -> DiagnosticsDecodeResult.Success(encoded.removePrefix("v2;ok;"))
        else -> DiagnosticsDecodeResult.Success(encoded)
    }

internal fun decodePlannedRanges(encoded: String): PlannedRangesDecodeResult {
    val trimmed = encoded.trim()
    if (trimmed == "v2;error") return PlannedRangesDecodeResult.NativeError
    if (trimmed == "v2;ok") return PlannedRangesDecodeResult.Success(emptyList())
    if (trimmed.startsWith("v2;ok;")) {
        return PlannedRangesDecodeResult.Success(decodePlannedRangeEntries(trimmed.removePrefix("v2;ok;")))
    }

    // v1 was the pre-status protocol. Keep decoding successful v1 payloads so a
    // staged native-library upgrade does not break an already-running process.
    if (trimmed.isEmpty() || trimmed == "v1") {
        return PlannedRangesDecodeResult.Success(emptyList())
    }
    require(trimmed.startsWith("v1;")) { "Unsupported planned range format" }
    return PlannedRangesDecodeResult.Success(decodePlannedRangeEntries(trimmed.removePrefix("v1;")))
}

private fun decodePlannedRangeEntries(entries: String): List<PlannedRemoteRange> =
    entries
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

class ComicNativeException(message: String) : RuntimeException(message)
