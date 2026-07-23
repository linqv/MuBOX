package com.example.comicdav.video.proxy

import com.example.comicdav.core.remote.ContentRange
import com.example.comicdav.core.remote.RemoteFileInfo
import com.example.comicdav.core.remote.WebDavClient
import com.example.comicdav.video.VideoPlaybackMemoryBudget
import com.example.comicdav.core.model.media.WebDavVideoOpenRequest
import com.example.comicdav.core.model.settings.VideoProxySettings
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.withContext

class MuBoxVideoProxy(
    private val clientProvider: suspend (String) -> WebDavClient? = { null },
    private val coroutineScope: CoroutineScope,
    private val portRange: IntRange = 0..0,
    private val requestHeaderTimeoutMillis: Int = DEFAULT_REQUEST_HEADER_TIMEOUT_MILLIS,
    private val maxRequestHeaderBytes: Int = DEFAULT_MAX_REQUEST_HEADER_BYTES,
    private val maxRequestsPerConnection: Int = DEFAULT_MAX_REQUESTS_PER_CONNECTION,
    private val maxConcurrentConnections: Int = DEFAULT_MAX_CONCURRENT_CONNECTIONS,
    private val memoryCacheMaxBytes: Long = VideoPlaybackMemoryBudget.current().proxyBytes,
    private val serverSocketFactory: (host: String, port: Int) -> ServerSocket = { host, port ->
        ServerSocket().apply {
            bind(InetSocketAddress(InetAddress.getByName(host), port), 50)
        }
    },
) : Closeable {
    init {
        require(requestHeaderTimeoutMillis > 0) { "requestHeaderTimeoutMillis must be positive" }
        require(maxRequestHeaderBytes > 0) { "maxRequestHeaderBytes must be positive" }
        require(maxRequestsPerConnection > 0) { "maxRequestsPerConnection must be positive" }
        require(maxConcurrentConnections > 0) { "maxConcurrentConnections must be positive" }
        require(memoryCacheMaxBytes >= 0L) { "memoryCacheMaxBytes must not be negative" }
    }

    private val ownerJob = SupervisorJob(coroutineScope.coroutineContext[Job])
    private val proxyScope = CoroutineScope(coroutineScope.coroutineContext + ownerJob)
    private val registry = StreamRegistry()
    private val statsStore = VideoProxyStatsStore()
    private val seekOptimizer = VideoSeekOptimizer(
        coroutineScope = proxyScope,
        cache = VideoRangeMemoryCache(maxBytes = memoryCacheMaxBytes),
        statsSink = statsStore,
    )
    private val closed = AtomicBoolean(false)
    private val startMutex = Mutex()
    private val connectionPermits = Semaphore(maxConcurrentConnections)
    private val clientSockets = ConcurrentHashMap.newKeySet<Socket>()
    @Volatile
    private var serverSocket: ServerSocket? = null
    private var acceptJob: Job? = null

    val baseUrl: String
        get() = "http://127.0.0.1:${serverSocket?.localPort ?: error("proxy not started")}"
    suspend fun start() {
        startMutex.withLock {
            check(!closed.get()) { "proxy is closed" }
            if (serverSocket != null) return
            val socket = withContext(Dispatchers.IO) {
                bindPort()
            }
            serverSocket = socket
            if (closed.get()) {
                serverSocket = null
                runCatching { socket.close() }
                error("proxy was closed while starting")
            }
            val job = proxyScope.launch(Dispatchers.IO) { acceptLoop(socket) }
            acceptJob = job
            if (closed.get()) {
                serverSocket = null
                runCatching { socket.close() }
                job.cancel()
                error("proxy was closed while starting")
            }
        }
    }

    fun register(
        request: WebDavVideoOpenRequest,
        proxySettings: VideoProxySettings = VideoProxySettings.DEFAULT,
    ): String =
        register(request, proxySettings) {
            clientProvider(request.accountId)
        }

    fun register(
        request: WebDavVideoOpenRequest,
        proxySettings: VideoProxySettings = VideoProxySettings.DEFAULT,
        openClient: suspend () -> WebDavClient?,
    ): String {
        val streamId = UUID.randomUUID().toString()
        registry.put(
            streamId,
            RegisteredVideoStream(
                request = VideoStreamRequest(
                    streamId = streamId,
                    accountId = request.accountId,
                    remotePath = request.remotePath,
                    displayName = request.displayName,
                    size = request.size,
                    etag = request.etag,
                    lastModified = request.lastModified,
                    mimeType = request.mimeType,
                    proxySettings = proxySettings,
                ),
                openClient = openClient,
            ),
        )
        statsStore.registerStream(streamId)
        return "$baseUrl/stream/$streamId/${request.displayName.toUrlPathSegment()}"
    }

    fun unregister(streamId: String): Boolean {
        val removed = registry.remove(streamId) != null
        seekOptimizer.removeStream(streamId)
        statsStore.removeStream(streamId)
        return removed
    }

    fun statistics(streamId: String): VideoProxyRuntimeStats? =
        statsStore.snapshot(streamId)

    private fun bindPort(): ServerSocket {
        var lastError: IOException? = null
        for (port in portRange) {
            try {
                return serverSocketFactory(LOOPBACK_HOST, port)
            } catch (error: IOException) {
                lastError = error
            }
        }
        throw IOException("Unable to bind video proxy port", lastError)
    }

    private suspend fun acceptLoop(socket: ServerSocket) {
        while (!closed.get()) {
            val client = try {
                socket.accept()
            } catch (error: IOException) {
                if (closed.get() || socket.isClosed) {
                    break
                }
                logAcceptFailure(error)
                continue
            }
            if (closed.get()) {
                client.closeQuietly()
                break
            }
            if (!connectionPermits.tryAcquire()) {
                client.closeQuietly()
                continue
            }
            clientSockets += client
            val connectionJob = proxyScope.launch(Dispatchers.IO, start = kotlinx.coroutines.CoroutineStart.LAZY) {
                try {
                    handleConnection(client)
                } catch (error: CancellationException) {
                    throw error
                } catch (error: IOException) {
                    logClientDisconnect(error)
                } catch (error: Exception) {
                    logConnectionFailure(error)
                } finally {
                    releaseClient(client)
                }
            }
            // A close can cancel ownerJob after accept() but before this coroutine starts. A
            // completion hook guarantees that socket/permit cleanup still runs in that window.
            connectionJob.invokeOnCompletion { releaseClient(client) }
            connectionJob.start()
        }
    }

    private fun releaseClient(client: Socket) {
        client.closeQuietly()
        if (clientSockets.remove(client)) {
            connectionPermits.release()
        }
    }

    private suspend fun handleConnection(socket: Socket) {
        socket.soTimeout = requestHeaderTimeoutMillis
        val writer = LocalHttpResponseWriter(socket.getOutputStream())
        var handledRequests = 0
        while (handledRequests < maxRequestsPerConnection) {
            val headerBlock = try {
                readRequestHeader(socket) ?: return
            } catch (_: RequestHeaderTooLarge) {
                writeSimpleResponse(writer, 431)
                return
            }
            val request = LocalHttpRequest.parse(headerBlock) ?: return
            val requestAllowsKeepAlive =
                request.allowsPersistentConnection && handledRequests + 1 < maxRequestsPerConnection
            val keepAlive = handleRequest(writer, request, requestAllowsKeepAlive)
            handledRequests += 1
            if (!keepAlive) return
        }
    }

    private suspend fun handleRequest(
        writer: LocalHttpResponseWriter,
        request: LocalHttpRequest,
        requestAllowsKeepAlive: Boolean,
    ): Boolean {
        if (!request.path.startsWith("/stream/")) {
            return writeSimpleResponse(writer, 404)
        }
        val streamId = URLDecoder.decode(
            request.path.removePrefix("/stream/").substringBefore('/').substringBefore('?'),
            Charsets.UTF_8.name(),
        )
        val entry = registry.get(streamId) ?: run {
            return writeSimpleResponse(writer, 404)
        }
        return when (request.method.uppercase()) {
            "HEAD" -> handleHead(writer, entry, requestAllowsKeepAlive)
            "GET" -> handleGet(writer, request.header("Range"), entry, requestAllowsKeepAlive)
            else -> writeSimpleResponse(writer, 405)
        }
    }

    private fun readRequestHeader(socket: Socket): String? {
        val input = socket.getInputStream()
        val buffer = ByteArrayOutputStream()
        var matchedTerminatorBytes = 0
        var previousByte = -1
        while (true) {
            val next = input.read()
            if (next == -1) return null
            buffer.write(next)
            if (buffer.size() > maxRequestHeaderBytes) {
                throw RequestHeaderTooLarge()
            }
            matchedTerminatorBytes = if (next == HEADER_TERMINATOR[matchedTerminatorBytes].toInt()) {
                matchedTerminatorBytes + 1
            } else if (next == HEADER_TERMINATOR[0].toInt()) {
                1
            } else {
                0
            }
            if (matchedTerminatorBytes == HEADER_TERMINATOR.size ||
                (previousByte == '\n'.code && next == '\n'.code)
            ) {
                return buffer.toString(Charsets.ISO_8859_1.name())
            }
            previousByte = next
        }
    }

    private suspend fun handleHead(
        writer: LocalHttpResponseWriter,
        entry: RegisteredVideoStream,
        requestAllowsKeepAlive: Boolean,
    ): Boolean {
        val request = entry.request
        val info = runCatchingCancellable {
            val client = entry.client() ?: return@runCatchingCancellable null
            request.size?.let {
                RemoteFileInfo(request.remotePath, it, request.etag, request.lastModified, true)
            } ?: client.head(request.remotePath)
        }.getOrElse { error ->
            logProxyFailure("HEAD metadata", request, error)
            return writeSimpleResponse(writer, 502)
        } ?: run {
            return writeSimpleResponse(writer, 404)
        }
        val keepAlive = requestAllowsKeepAlive && info.size >= 0L
        writer.write(
            code = 200,
            headers = mapOf(
                "Content-Type" to (request.mimeType ?: "application/octet-stream"),
                "Accept-Ranges" to "bytes",
            ),
            contentLength = info.size.takeIf { it >= 0L },
            connection = keepAlive.toLocalHttpConnection(),
        )
        return keepAlive
    }

    private suspend fun handleGet(
        writer: LocalHttpResponseWriter,
        rangeHeader: String?,
        entry: RegisteredVideoStream,
        requestAllowsKeepAlive: Boolean,
    ): Boolean {
        val request = entry.request
        val client = entry.client() ?: run {
            return writeSimpleResponse(writer, 404)
        }
        val info = runCatchingCancellable {
            request.size?.let {
                RemoteFileInfo(request.remotePath, it, request.etag, request.lastModified, true)
            } ?: client.head(request.remotePath)
        }.getOrElse { error ->
            logProxyFailure("GET metadata", request, error)
            return writeSimpleResponse(writer, 502)
        }
        val rangeResult = parseRange(rangeHeader, info.size)
        if (rangeResult is ParsedRange.Invalid) {
            return writeSimpleResponse(writer, 416, contentRange = "bytes */${info.size}")
        }
        val range = when (rangeResult) {
            is ParsedRange.Valid -> rangeResult
            null -> null
            ParsedRange.Invalid -> null
        }
        statsStore.updateCurrentRange(request.streamId, range?.toRangeHeaderValue())
        statsStore.updateDiagnosticMessage(request.streamId, null)
        statsStore.updateRemoteStatus(request.streamId, null)
        val requestCancellation = RequestCancellation()
        if (!registry.addActive(request.streamId, requestCancellation)) {
            return writeSimpleResponse(writer, 404)
        }
        val response = try {
            if (range == null) {
                client.openFullStream(request.remotePath, requestCancellation::add)
            } else if (shouldUseSeekOptimizer(request, range)) {
                runCatchingCancellable {
                    seekOptimizer.openRangeStream(
                        client = client,
                        request = request,
                        totalSize = info.size,
                        start = range.start,
                        endInclusive = range.endInclusive,
                        settings = request.proxySettings,
                        registerCancellation = requestCancellation::add,
                    )
                }.getOrElse { error ->
                    VideoProxyDiagnostics(request.proxySettings.diagnosticsMode).summary {
                        "fallback stream=${VideoProxyDiagnostics.redactedStreamId(request.streamId)} reason=optimized_range_failed " +
                            "error=${error.javaClass.simpleName}"
                    }
                    logProxyFailure("GET optimized stream", request, error)
                    client.openRangeStream(
                        path = request.remotePath,
                        start = range.start,
                        endInclusive = range.endInclusive,
                        registerCancellation = requestCancellation::add,
                    )
                }
            } else if (range.isOpenEnded) {
                seekOptimizer.openStreamingRangeWithCacheWarmup(
                    client = client,
                    request = request,
                    totalSize = info.size,
                    start = range.start,
                    endInclusive = range.endInclusive,
                    settings = request.proxySettings,
                    registerCancellation = requestCancellation::add,
                )
            } else {
                client.openRangeStream(
                    path = request.remotePath,
                    start = range.start,
                    endInclusive = range.endInclusive,
                    registerCancellation = requestCancellation::add,
                )
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            logProxyFailure("GET stream", request, error)
            return writeSimpleResponse(writer, 502)
        } finally {
            registry.removeActive(request.streamId, requestCancellation)
        }
        statsStore.updateRemoteStatus(request.streamId, response.statusCode)
        val responseCloseable = Closeable { response.close() }
        if (!registry.addActive(request.streamId, responseCloseable)) {
            responseCloseable.close()
            return writeSimpleResponse(writer, 404)
        }
        try {
            val statusCode = if (range == null) 200 else 206
            if (range == null && !isCompleteFullBodyResponse(response, info.size)) {
                logProxyFailure(
                    "GET full-body validation",
                    request,
                    IOException("Remote full-body range did not match known size ${info.size}"),
                )
                return writeSimpleResponse(writer, 502)
            }
            val contentRange = if (statusCode == 206) {
                response.contentRange?.withKnownTotalSize(response.totalSize, info.size)
                    ?: ContentRange(
                        start = range!!.start,
                        endInclusive = range.endInclusive,
                        totalSize = response.totalSize ?: info.size,
                    )
            } else {
                null
            }
            val responseContentLength = when {
                statusCode == 206 && contentRange != null -> contentRange.endInclusive - contentRange.start + 1L
                statusCode == 200 && response.contentLength < 0 -> info.size
                else -> response.contentLength
            }
            val headers = linkedMapOf(
                "Content-Type" to (response.contentType ?: request.mimeType ?: "application/octet-stream"),
                "Accept-Ranges" to "bytes",
            )
            val keepAlive = requestAllowsKeepAlive && responseContentLength >= 0L
            val bodyResult = writer.write(
                code = statusCode,
                headers = headers,
                contentLength = responseContentLength.takeIf { it >= 0L },
                contentRange = contentRange?.toHeaderValue(),
                connection = keepAlive.toLocalHttpConnection(),
                body = response.stream,
            )
            if (bodyResult == LocalHttpBodyWriteResult.LENGTH_MISMATCH) {
                logProxyFailure(
                    "GET body length",
                    request,
                    IOException("Response body length did not match Content-Length $responseContentLength"),
                )
            }
            return keepAlive && bodyResult == LocalHttpBodyWriteResult.COMPLETE
        } finally {
            registry.removeActive(request.streamId, responseCloseable)
            responseCloseable.close()
        }
    }

    private fun parseRange(rangeHeader: String?, totalSize: Long): ParsedRange? {
        val value = rangeHeader ?: return null
        val match = RANGE_REGEX.matchEntire(value.trim()) ?: return ParsedRange.Invalid
        val startValue = match.groupValues[1]
        val endValue = match.groupValues[2]
        if (startValue.isBlank()) {
            val suffixLength = endValue.toLongOrNull() ?: return ParsedRange.Invalid
            if (suffixLength <= 0 || totalSize <= 0) return ParsedRange.Invalid
            val start = (totalSize - suffixLength).coerceAtLeast(0L)
            return ParsedRange.Valid(
                start = start,
                endInclusive = totalSize - 1,
                seekOptimizationEligible = false,
                isOpenEnded = false,
            )
        }
        val start = startValue.toLongOrNull() ?: return ParsedRange.Invalid
        val hasExplicitEnd = endValue.isNotBlank()
        val end = endValue.takeIf { hasExplicitEnd }?.toLongOrNull()
        if (start < 0 || start >= totalSize) return ParsedRange.Invalid
        if (end != null && end < start) return ParsedRange.Invalid
        val boundedEnd = (end ?: (start + DEFAULT_STREAM_CHUNK_BYTES - 1)).coerceAtMost(totalSize - 1)
        return ParsedRange.Valid(
            start = start,
            endInclusive = boundedEnd,
            seekOptimizationEligible = hasExplicitEnd,
            isOpenEnded = !hasExplicitEnd,
        )
    }

    private fun shouldUseSeekOptimizer(
        request: VideoStreamRequest,
        range: ParsedRange.Valid,
    ): Boolean {
        if (!request.proxySettings.seekOptimizationEnabled) return false
        if (!range.seekOptimizationEligible) return false
        if (range.byteCount > MAX_OPTIMIZED_RESPONSE_BYTES) return false
        return segmentIndexFor(range.start) == segmentIndexFor(range.endInclusive)
    }

    private fun segmentIndexFor(byteOffset: Long): Long =
        byteOffset / OPTIMIZER_SEGMENT_BYTES

    private fun writeSimpleResponse(
        writer: LocalHttpResponseWriter,
        code: Int,
        contentRange: String? = null,
    ): Boolean {
        writer.write(
            code = code,
            contentLength = 0L,
            contentRange = contentRange,
            connection = LocalHttpConnection.CLOSE,
        )
        return false
    }

    private fun ContentRange.toHeaderValue(): String =
        "bytes $start-$endInclusive/$totalSize"

    private fun ParsedRange.Valid.toRangeHeaderValue(): String =
        "bytes=$start-$endInclusive"

    private fun Boolean.toLocalHttpConnection(): LocalHttpConnection =
        if (this) LocalHttpConnection.KEEP_ALIVE else LocalHttpConnection.CLOSE

    private fun ContentRange.withKnownTotalSize(responseTotalSize: Long?, fallbackTotalSize: Long): ContentRange =
        if (totalSize >= 0) {
            this
        } else {
            copy(totalSize = responseTotalSize?.takeIf { it >= 0 } ?: fallbackTotalSize)
        }

    private fun isCompleteFullBodyResponse(response: com.example.comicdav.core.remote.WebDavStreamResponse, totalSize: Long): Boolean {
        if (response.statusCode == 200) {
            return response.contentLength < 0 || response.contentLength == totalSize
        }
        val range = response.contentRange ?: return false
        return response.statusCode == 206 &&
            response.contentLength == totalSize &&
            range.start == 0L &&
            range.endInclusive == totalSize - 1
    }

    private suspend inline fun <T> runCatchingCancellable(crossinline block: suspend () -> T): Result<T> =
        try {
            Result.success(block())
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            Result.failure(error)
        }

    private fun logProxyFailure(operation: String, entry: VideoStreamRequest, error: Throwable) {
        val message = error.message
            ?.let(VideoProxyDiagnostics::redactCredentials)
            ?.takeIf { it.isNotBlank() }
            ?.let { ": $it" }
            .orEmpty()
        System.err.println(
            "Video proxy $operation failed stream=${VideoProxyDiagnostics.redactedStreamId(entry.streamId)} " +
                "error=${error.javaClass.simpleName}$message",
        )
    }

    private fun logClientDisconnect(error: IOException) {
        System.err.println("Video proxy client disconnected: ${error.message ?: error::class.java.simpleName}")
    }

    private fun logAcceptFailure(error: IOException) {
        System.err.println("Video proxy accept failed: ${error.message ?: error::class.java.simpleName}")
    }

    private fun logConnectionFailure(error: Throwable) {
        System.err.println("Video proxy connection failed: ${error.message ?: error::class.java.simpleName}")
    }

    override fun close() {
        beginClose()
    }

    suspend fun awaitClosed(timeoutMillis: Long = CLOSE_JOIN_TIMEOUT_MILLIS): Boolean {
        require(timeoutMillis > 0L) { "timeoutMillis must be positive" }
        beginClose()
        return withTimeoutOrNull(timeoutMillis) {
            ownerJob.join()
            clientSockets.forEach { it.closeQuietly() }
            clientSockets.clear()
            true
        } ?: false
    }

    private fun beginClose() {
        if (!closed.compareAndSet(false, true)) return
        serverSocket?.close()
        clientSockets.forEach { it.closeQuietly() }
        registry.close()
        seekOptimizer.close()
        ownerJob.cancel()
        // Close a second time after cancellation to cover accept() passing its closed check just
        // before beginClose acquired the CPU. invokeOnCompletion handles any still-later add.
        clientSockets.forEach { it.closeQuietly() }
        serverSocket = null
        acceptJob = null
        statsStore.clear()
    }

    private fun Socket.closeQuietly() {
        runCatching { close() }
    }

    private sealed class ParsedRange {
        data class Valid(
            val start: Long,
            val endInclusive: Long,
            val seekOptimizationEligible: Boolean,
            val isOpenEnded: Boolean,
        ) : ParsedRange() {
            val byteCount: Long
                get() = endInclusive - start + 1L
        }
        data object Invalid : ParsedRange()
    }

    private class RequestHeaderTooLarge : IOException()

    private class RequestCancellation : Closeable {
        private val closed = AtomicBoolean(false)
        private val closeables = java.util.concurrent.ConcurrentHashMap.newKeySet<Closeable>()

        fun add(closeable: Closeable) {
            if (closed.get()) {
                closeable.closeQuietly()
                return
            }
            closeables += closeable
            if (closed.get()) {
                closeables -= closeable
                closeable.closeQuietly()
            }
        }

        override fun close() {
            if (!closed.compareAndSet(false, true)) return
            closeables.forEach { closeable ->
                closeable.closeQuietly()
                closeables -= closeable
            }
        }

        private fun Closeable.closeQuietly() {
            runCatching { close() }
        }
    }

    companion object {
        private const val LOOPBACK_HOST = "127.0.0.1"
        private const val DEFAULT_STREAM_CHUNK_BYTES = 8L * 1024L * 1024L
        private const val OPTIMIZER_SEGMENT_BYTES = VideoRangeMemoryCache.DEFAULT_SEGMENT_BYTES
        private const val MAX_OPTIMIZED_RESPONSE_BYTES = OPTIMIZER_SEGMENT_BYTES
        private const val DEFAULT_REQUEST_HEADER_TIMEOUT_MILLIS = 10_000
        private const val DEFAULT_MAX_REQUEST_HEADER_BYTES = 16 * 1024
        private const val DEFAULT_MAX_REQUESTS_PER_CONNECTION = 64
        private const val DEFAULT_MAX_CONCURRENT_CONNECTIONS = 8
        private const val CLOSE_JOIN_TIMEOUT_MILLIS = 5_000L
        private val HEADER_TERMINATOR = byteArrayOf('\r'.code.toByte(), '\n'.code.toByte(), '\r'.code.toByte(), '\n'.code.toByte())
        private val RANGE_REGEX = Regex("bytes=(\\d*)-(\\d*)", RegexOption.IGNORE_CASE)

        fun streamIdFromUrl(url: String): String =
            url.substringAfter("/stream/").substringBefore('/').substringBefore('?')

        private fun String.toUrlPathSegment(): String {
            val fileName = substringAfterLast('/').substringAfterLast('\\').takeIf { it.isNotBlank() } ?: "stream"
            return URLEncoder.encode(fileName, Charsets.UTF_8.name()).replace("+", "%20")
        }
    }
}
