package org.mubox.reader.nativebridge

import androidx.annotation.WorkerThread
import org.mubox.reader.core.ports.ComicReaderSession
import org.mubox.reader.core.ports.PlannedRemoteRange
import org.mubox.reader.core.ports.ReconciledPrefetchPlan
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

class ComicEngine(
    private val native: ComicNativeFacade = ComicNative,
) {
    @WorkerThread
    fun openLocal(path: String): ComicReaderSession {
        val handle = native.openLocal(path)
        return openChecked(handle)
    }

    @WorkerThread
    fun openLocalFd(
        fd: Int,
        size: Long,
        format: String,
    ): ComicReaderSession {
        val handle = native.openLocalFd(fd, size, format)
        return openChecked(handle)
    }

    // Rust synchronously calls the registered transport on worker threads for network misses.
    @WorkerThread
    fun openRemote(
        fileId: Long,
        size: Long,
        cacheDir: File,
        comicKey: String,
        validator: String,
        webDavPrefetchPageCount: Int = 4,
    ): ComicReaderSession {
        val handle = native.openRemoteCachedV1(
            fileId,
            size,
            cacheDir.absolutePath,
            comicKey,
            validator,
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
    private val isClosed = AtomicBoolean(false)
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
    override fun reconcilePrefetchPlan(
        pageIndex: Int,
        networkClass: Int,
        activeRanges: List<PlannedRemoteRange>,
        completedRanges: List<PlannedRemoteRange>,
        byteBudget: Long,
    ): ReconciledPrefetchPlan {
        val encoded = native.reconcilePrefetchPlanV1(
            handle = handle,
            pageIndex = pageIndex,
            networkClass = networkClass,
            forwardPrefetchPageCount = forwardPrefetchPageCount,
            byteBudget = byteBudget,
            activeRanges = PrefetchPlanWireV1.encodeRanges(activeRanges),
            completedRanges = PrefetchPlanWireV1.encodeRanges(completedRanges),
        )
        val decoded = encoded?.let(PrefetchPlanWireV1::decodePlan)
        if (decoded == null) {
            throw ComicNativeException(
                native.lastErrorMessage().ifBlank { "Failed to reconcile native prefetch plan" },
            )
        }
        return decoded
    }

    @WorkerThread
    override fun prefetchRange(start: Long, endInclusive: Long): Boolean {
        return prefetchRange(
            start = start,
            endInclusive = endInclusive,
            priority = 0,
            protectedRanges = emptyList(),
        )
    }

    @WorkerThread
    override fun prefetchRange(
        start: Long,
        endInclusive: Long,
        priority: Int,
        protectedRanges: List<LongRange>,
    ): Boolean {
        if (rangeProviderFileId == null) return false
        val result = native.prefetchRemoteRangeV1(
            handle = handle,
            start = start,
            endInclusive = endInclusive,
            priority = priority,
            protectedRanges = RangeIoWireV1.encodeProtectedRanges(protectedRanges),
        )
        if (result < 0) {
            throw ComicNativeException(
                native.lastErrorMessage().ifBlank { "Failed to prefetch native range" },
            )
        }
        return result > 0
    }

    override fun cancelPrefetches() {
        if (rangeProviderFileId == null) return
        native.cancelRemoteIoV1(handle)
    }

    override fun close() {
        if (isClosed.compareAndSet(false, true)) {
            try {
                native.close(handle)
            } finally {
                onClose()
            }
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
        else -> throw IllegalArgumentException("Unsupported diagnostics format")
    }

internal fun decodePlannedRanges(encoded: String): PlannedRangesDecodeResult {
    val trimmed = encoded.trim()
    if (trimmed == "v2;error") return PlannedRangesDecodeResult.NativeError
    if (trimmed == "v2;ok") return PlannedRangesDecodeResult.Success(emptyList())
    if (trimmed.startsWith("v2;ok;")) {
        return PlannedRangesDecodeResult.Success(decodePlannedRangeEntries(trimmed.removePrefix("v2;ok;")))
    }

    throw IllegalArgumentException("Unsupported planned range format")
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
