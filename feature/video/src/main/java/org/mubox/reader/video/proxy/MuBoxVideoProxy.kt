package org.mubox.reader.video.proxy

import org.mubox.reader.core.diagnostics.DiagnosticCategory
import org.mubox.reader.core.diagnostics.Diagnostics
import org.mubox.reader.core.diagnostics.NoopDiagnostics
import org.mubox.reader.core.model.media.WebDavVideoOpenRequest
import org.mubox.reader.core.model.settings.VideoProxySettings
import org.mubox.reader.core.remote.WebDavClient
import org.mubox.reader.video.VideoPlaybackMemoryBudget
import java.io.Closeable
import java.net.URLEncoder
import java.util.UUID

/** Thin lifecycle wrapper around one native media-proxy engine. */
class MuBoxVideoProxy(
    private val clientProvider: suspend (String) -> WebDavClient? = { null },
    private val portRange: IntRange = 0..0,
    private val requestHeaderTimeoutMillis: Int = DEFAULT_REQUEST_HEADER_TIMEOUT_MILLIS,
    private val maxRequestHeaderBytes: Int = DEFAULT_MAX_REQUEST_HEADER_BYTES,
    private val maxRequestsPerConnection: Int = DEFAULT_MAX_REQUESTS_PER_CONNECTION,
    private val maxConcurrentConnections: Int = DEFAULT_MAX_CONCURRENT_CONNECTIONS,
    private val memoryCacheMaxBytes: Long = VideoPlaybackMemoryBudget.current().proxyBytes,
    private val diagnostics: Diagnostics = NoopDiagnostics,
    private val native: MediaProxyNativeFacade = MediaProxyNative,
) : Closeable {
    private val lifecycleLock = Any()
    private val streams = LinkedHashMap<String, NativeStream>()
    private var proxyHandle = NO_HANDLE
    private var boundPort = NO_PORT
    private var closed = false

    init {
        require(!portRange.isEmpty()) { "portRange must not be empty" }
        require(portRange.first in MIN_PORT..MAX_PORT && portRange.last in MIN_PORT..MAX_PORT) {
            "portRange must contain valid TCP ports"
        }
        require(requestHeaderTimeoutMillis > 0) { "requestHeaderTimeoutMillis must be positive" }
        require(maxRequestHeaderBytes > 0) { "maxRequestHeaderBytes must be positive" }
        require(maxRequestsPerConnection > 0) { "maxRequestsPerConnection must be positive" }
        require(maxConcurrentConnections > 0) { "maxConcurrentConnections must be positive" }
        require(memoryCacheMaxBytes >= 0L) { "memoryCacheMaxBytes must not be negative" }
    }

    val baseUrl: String
        get() = synchronized(lifecycleLock) {
            check(boundPort > 0) { "proxy not started" }
            "http://$LOOPBACK_HOST:$boundPort"
        }

    suspend fun start() {
        synchronized(lifecycleLock) {
            check(!closed) { "proxy is closed" }
            if (boundPort > 0) return

            val createdHandle = nativeCall("create") {
                native.proxyCreateV1(
                    cacheBytes = memoryCacheMaxBytes,
                    portStart = portRange.first,
                    portEnd = portRange.last,
                    headerTimeout = requestHeaderTimeoutMillis,
                    maxHeaderBytes = maxRequestHeaderBytes,
                    maxRequestsPerConnection = maxRequestsPerConnection,
                    maxConnections = maxConcurrentConnections,
                )
            }
            if (createdHandle == NO_HANDLE) throw nativeFailure("create")

            val startedPort = try {
                nativeCall("start") { native.proxyStartV1(createdHandle) }
            } catch (error: Throwable) {
                runCatching { native.proxyCloseV1(createdHandle) }
                throw error
            }
            if (startedPort <= 0) {
                val error = nativeFailure("start")
                runCatching { native.proxyCloseV1(createdHandle) }
                throw error
            }
            proxyHandle = createdHandle
            boundPort = startedPort
        }
    }

    fun register(
        request: WebDavVideoOpenRequest,
        proxySettings: VideoProxySettings = VideoProxySettings.DEFAULT,
    ): String = register(request, proxySettings) {
        clientProvider(request.accountId)
    }

    fun register(
        request: WebDavVideoOpenRequest,
        proxySettings: VideoProxySettings = VideoProxySettings.DEFAULT,
        openClient: suspend () -> WebDavClient?,
    ): String = synchronized(lifecycleLock) {
        check(!closed) { "proxy is closed" }
        val activeProxyHandle = proxyHandle
        check(activeProxyHandle != NO_HANDLE && boundPort > 0) { "proxy not started" }

        val streamId = UUID.randomUUID().toString()
        val bridge = MediaProxyNetworkBridge(
            streamId = streamId,
            remotePath = request.remotePath,
            knownSize = request.size,
            knownLastModified = request.lastModified,
            openClient = openClient,
            diagnostics = diagnostics,
        )
        val streamHandle = try {
            nativeCall("stream_create", streamId) {
                native.streamCreateV1(
                    proxy = activeProxyHandle,
                    bridge = bridge,
                    routeToken = streamId,
                    size = request.size ?: UNKNOWN_SIZE,
                    mime = request.mimeType ?: DEFAULT_MIME_TYPE,
                    seekEnabled = proxySettings.seekOptimizationEnabled,
                    prefetchSegments = proxySettings.forwardPrefetchMode.segmentCount,
                )
            }
        } catch (error: Throwable) {
            bridge.close()
            throw error
        }
        if (streamHandle == NO_HANDLE) {
            bridge.close()
            throw nativeFailure("stream_create", streamId)
        }
        streams[streamId] = NativeStream(streamHandle, bridge)
        "$baseUrl/stream/$streamId/${request.displayName.toUrlPathSegment()}"
    }

    fun unregister(streamId: String): Boolean {
        val stream = synchronized(lifecycleLock) { streams.remove(streamId) } ?: return false
        stream.bridge.close()
        runCatching { native.streamCloseV1(stream.handle) }
            .onFailure { error -> logNativeFailure("stream_close", streamId, error) }
        return true
    }

    fun statistics(streamId: String): VideoProxyRuntimeStats? {
        val streamHandle = synchronized(lifecycleLock) { streams[streamId]?.handle } ?: return null
        val encoded: String? = try {
            native.streamStatsV1(streamHandle)
        } catch (error: Throwable) {
            logNativeFailure("stream_stats", streamId, error)
            return null
        }
        if (encoded == null) {
            nativeFailure("stream_stats", streamId)
            return null
        }
        return VideoProxyRuntimeStatsJson.decode(encoded).also { stats ->
            if (stats == null && encoded.isNotBlank() && encoded.trim() != "null") {
                logNativeFailure(
                    operation = "stream_stats_decode",
                    streamId = streamId,
                    error = IllegalArgumentException("Native proxy returned malformed statistics"),
                )
            }
        }
    }

    override fun close() {
        val closing = synchronized(lifecycleLock) {
            if (closed) return
            closed = true
            val snapshot = streams.values.toList()
            streams.clear()
            val handle = proxyHandle
            proxyHandle = NO_HANDLE
            boundPort = NO_PORT
            ClosingProxy(handle, snapshot)
        }

        // Cancel network work first so a native close cannot wait indefinitely on a blocked fetch.
        closing.streams.forEach { it.bridge.close() }
        closing.streams.forEach { stream ->
            runCatching { native.streamCloseV1(stream.handle) }
                .onFailure { error -> logNativeFailure("stream_close", error = error) }
        }
        if (closing.handle != NO_HANDLE) {
            runCatching { native.proxyCloseV1(closing.handle) }
                .onFailure { error -> logNativeFailure("close", error = error) }
        }
    }

    private inline fun <T> nativeCall(
        operation: String,
        streamId: String? = null,
        block: () -> T,
    ): T = try {
        block()
    } catch (error: Throwable) {
        logNativeFailure(operation, streamId, error)
        throw MediaProxyNativeException(nativeErrorMessage("Native media proxy $operation failed"), error)
    }

    private fun nativeFailure(operation: String, streamId: String? = null): MediaProxyNativeException {
        val error = MediaProxyNativeException(nativeErrorMessage("Native media proxy $operation failed"))
        logNativeFailure(operation, streamId, error)
        return error
    }

    private fun nativeErrorMessage(fallback: String): String =
        runCatching { native.lastErrorMessageV1() }
            .getOrNull()
            ?.takeIf { it.isNotBlank() }
            ?: fallback

    private fun logNativeFailure(
        operation: String,
        streamId: String? = null,
        error: Throwable,
    ) {
        val stream = streamId?.let { " stream=${VideoProxyDiagnostics.redactedStreamId(it)}" }.orEmpty()
        runCatching {
            diagnostics.error(
                DiagnosticCategory.VIDEO,
                "video_proxy_native_failed operation=$operation$stream",
                error,
            )
        }
    }

    private data class NativeStream(
        val handle: Long,
        val bridge: MediaProxyNetworkBridge,
    )

    private data class ClosingProxy(
        val handle: Long,
        val streams: List<NativeStream>,
    )

    companion object {
        private const val LOOPBACK_HOST = "127.0.0.1"
        private const val MIN_PORT = 0
        private const val MAX_PORT = 65_535
        private const val NO_PORT = -1
        private const val NO_HANDLE = 0L
        private const val UNKNOWN_SIZE = -1L
        private const val DEFAULT_MIME_TYPE = "application/octet-stream"
        private const val DEFAULT_REQUEST_HEADER_TIMEOUT_MILLIS = 10_000
        private const val DEFAULT_MAX_REQUEST_HEADER_BYTES = 16 * 1024
        private const val DEFAULT_MAX_REQUESTS_PER_CONNECTION = 64
        private const val DEFAULT_MAX_CONCURRENT_CONNECTIONS = 8

        fun streamIdFromUrl(url: String): String =
            url.substringAfter("/stream/").substringBefore('/').substringBefore('?')

        private fun String.toUrlPathSegment(): String {
            val fileName = substringAfterLast('/').substringAfterLast('\\')
                .takeIf { it.isNotBlank() }
                ?: "stream"
            return URLEncoder.encode(fileName, Charsets.UTF_8.name()).replace("+", "%20")
        }
    }
}
