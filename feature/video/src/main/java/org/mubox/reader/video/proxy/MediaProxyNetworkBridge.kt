package org.mubox.reader.video.proxy

import org.mubox.reader.core.diagnostics.DiagnosticCategory
import org.mubox.reader.core.diagnostics.Diagnostics
import org.mubox.reader.core.diagnostics.NoopDiagnostics
import org.mubox.reader.core.remote.WebDavClient
import org.mubox.reader.core.remote.WebDavStreamResponse
import java.io.Closeable
import java.io.IOException
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.runBlocking

/**
 * One stream-scoped network adapter retained by the native engine.
 *
 * Rust owns HTTP parsing, range planning, caching and request coalescing. This bridge deliberately
 * keeps only the Android-aware WebDAV transport on the Kotlin side. Native callbacks arrive on
 * worker threads and are therefore synchronous; suspending WebDAV entry points are joined only at
 * this coarse network boundary.
 */
class MediaProxyNetworkBridge internal constructor(
    private val streamId: String,
    private val remotePath: String,
    private val knownSize: Long?,
    private val knownLastModified: Long?,
    private val openClient: suspend () -> WebDavClient?,
    private val diagnostics: Diagnostics = NoopDiagnostics,
) : Closeable {
    private val closed = AtomicBoolean(false)
    private val fetches = ConcurrentHashMap<Long, ActiveFetch>()
    private val lifecycleLock = Any()
    private val clientLock = Any()

    @Volatile
    private var clientInitialized = false
    private var cachedClient: WebDavClient? = null
    private var clientFailure: Throwable? = null

    /** Returns [size, lastModifiedOrMinusOne]. */
    fun headV1(): LongArray = callback("head") {
        ensureOpen()
        val requestSize = knownSize?.takeIf { it >= 0L }
        if (requestSize != null) {
            longArrayOf(requestSize, knownLastModified?.takeIf { it >= 0L } ?: UNKNOWN_VALUE)
        } else {
            val client = client()
            val info = runBlocking { client.head(remotePath) }
            longArrayOf(info.size, info.lastModified?.takeIf { it >= 0L } ?: UNKNOWN_VALUE)
        }
    }

    /**
     * Opens a fetch and returns
     * [status, contentLength, rangeStartOrMinusOne, rangeEndOrMinusOne, totalSizeOrMinusOne].
     */
    fun openFetchV1(
        requestId: Long,
        start: Long,
        endInclusive: Long,
        mode: Int,
    ): LongArray = callback("open_fetch") {
        ensureOpen()
        require(requestId > 0L) { "requestId must be positive" }
        val fetch = ActiveFetch()
        synchronized(lifecycleLock) {
            ensureOpen()
            check(fetches.putIfAbsent(requestId, fetch) == null) {
                "Fetch request $requestId is already active"
            }
        }
        try {
            when (mode) {
                MODE_RANGE -> {
                    require(start >= 0L) { "Range start must not be negative" }
                    require(endInclusive < 0L || endInclusive >= start) {
                        "Range end must not precede its start"
                    }
                }
                MODE_FULL -> Unit
                else -> throw IllegalArgumentException("Unknown media proxy fetch mode: $mode")
            }
            val client = client()
            val response = runBlocking {
                when (mode) {
                    MODE_RANGE -> client.openRangeStream(
                        path = remotePath,
                        start = start,
                        endInclusive = endInclusive.takeIf { it >= 0L },
                        registerCancellation = fetch::registerCancellation,
                    )

                    MODE_FULL -> client.openFullStream(
                        path = remotePath,
                        registerCancellation = fetch::registerCancellation,
                    )

                    else -> error("Fetch mode was validated before opening")
                }
            }
            fetch.attachResponse(response)
            response.toNativeMetadata()
        } catch (error: Throwable) {
            fetches.remove(requestId, fetch)
            fetch.close()
            throw error
        }
    }

    /** Reads directly into the fixed-size direct buffer supplied by native code. */
    fun readFetchIntoV1(requestId: Long, target: ByteBuffer): Int = callback("read_fetch") {
        ensureOpen()
        require(target.isDirect) { "Native fetch target must be a direct ByteBuffer" }
        if (!target.hasRemaining()) return@callback 0
        val fetch = fetches[requestId]
            ?: throw IOException("Fetch request $requestId is not active")
        fetch.readInto(target)
    }

    fun cancelFetchV1(requestId: Long) {
        callback("cancel_fetch") {
            fetches[requestId]?.cancel()
        }
    }

    fun closeFetchV1(requestId: Long) {
        callback("close_fetch") {
            fetches.remove(requestId)?.close()
        }
    }

    override fun close() {
        val closingFetches = synchronized(lifecycleLock) {
            if (!closed.compareAndSet(false, true)) return
            fetches.values.toList().also { fetches.clear() }
        }
        closingFetches.forEach(ActiveFetch::close)
    }

    private fun client(): WebDavClient {
        if (clientInitialized) return resolvedClient()
        synchronized(clientLock) {
            if (!clientInitialized) {
                try {
                    cachedClient = runBlocking { openClient() }
                    if (cachedClient == null) {
                        clientFailure = IOException("WebDAV client snapshot is unavailable")
                    }
                } catch (error: Throwable) {
                    clientFailure = error
                } finally {
                    clientInitialized = true
                }
            }
            return resolvedClient()
        }
    }

    private fun resolvedClient(): WebDavClient {
        clientFailure?.let { throw it }
        return cachedClient ?: throw IOException("WebDAV client snapshot is unavailable")
    }

    private fun ensureOpen() {
        check(!closed.get()) { "Media proxy network bridge is closed" }
    }

    private inline fun <T> callback(operation: String, block: () -> T): T =
        try {
            block()
        } catch (error: Throwable) {
            runCatching {
                diagnostics.error(
                    DiagnosticCategory.VIDEO,
                    "video_proxy_network_failed operation=$operation " +
                        "stream=${VideoProxyDiagnostics.redactedStreamId(streamId)}",
                    error,
                )
            }
            throw error
        }

    private fun WebDavStreamResponse.toNativeMetadata(): LongArray {
        val range = contentRange
        return longArrayOf(
            statusCode.toLong(),
            contentLength,
            range?.start ?: UNKNOWN_VALUE,
            range?.endInclusive ?: UNKNOWN_VALUE,
            totalSize ?: range?.totalSize ?: UNKNOWN_VALUE,
        )
    }

    private class ActiveFetch : Closeable {
        private val closed = AtomicBoolean(false)
        private val resources = ConcurrentHashMap.newKeySet<Closeable>()
        private val responseLock = Any()
        private val readLock = Any()

        private var response: WebDavStreamResponse? = null

        fun registerCancellation(closeable: Closeable) {
            if (closed.get()) {
                closeable.closeQuietly()
                return
            }
            resources += closeable
            if (closed.get() && resources.remove(closeable)) {
                closeable.closeQuietly()
            }
        }

        fun attachResponse(value: WebDavStreamResponse) {
            val attached = synchronized(responseLock) {
                if (closed.get()) {
                    false
                } else {
                    response = value
                    true
                }
            }
            if (!attached) {
                value.closeQuietly()
                throw IOException("Fetch was cancelled while opening")
            }
        }

        fun readInto(target: ByteBuffer): Int = synchronized(readLock) {
            check(!closed.get()) { "Fetch is closed" }
            val activeResponse = synchronized(responseLock) { response }
                ?: throw IOException("Fetch response is not open")
            activeResponse.readInto(target)
        }

        fun cancel() = close()

        override fun close() {
            if (!closed.compareAndSet(false, true)) return
            val activeResponse = synchronized(responseLock) {
                response.also { response = null }
            }
            activeResponse?.closeQuietly()
            resources.forEach { closeable ->
                if (resources.remove(closeable)) closeable.closeQuietly()
            }
        }

        private fun Closeable.closeQuietly() {
            runCatching { close() }
        }

        private fun WebDavStreamResponse.closeQuietly() {
            runCatching { close() }
        }
    }

    companion object {
        const val MODE_RANGE = 0
        const val MODE_FULL = 1
        private const val UNKNOWN_VALUE = -1L
    }
}
