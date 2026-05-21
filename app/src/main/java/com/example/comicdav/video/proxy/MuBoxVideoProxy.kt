package com.example.comicdav.video.proxy

import com.example.comicdav.network.ContentRange
import com.example.comicdav.network.RemoteFileInfo
import com.example.comicdav.network.WebDavClient
import com.example.comicdav.video.WebDavVideoOpenRequest
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.IOException
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class MuBoxVideoProxy(
    private val clientProvider: suspend (String) -> WebDavClient? = { null },
    private val coroutineScope: CoroutineScope,
    private val portRange: IntRange = 49152..65535,
    private val requestHeaderTimeoutMillis: Int = DEFAULT_REQUEST_HEADER_TIMEOUT_MILLIS,
    private val maxRequestHeaderBytes: Int = DEFAULT_MAX_REQUEST_HEADER_BYTES,
    private val serverSocketFactory: (host: String, port: Int) -> ServerSocket = { host, port ->
        ServerSocket().apply {
            bind(InetSocketAddress(InetAddress.getByName(host), port), 50)
        }
    },
) : Closeable {
    init {
        require(requestHeaderTimeoutMillis > 0) { "requestHeaderTimeoutMillis must be positive" }
        require(maxRequestHeaderBytes > 0) { "maxRequestHeaderBytes must be positive" }
    }

    private val registry = StreamRegistry()
    private val seekOptimizer = VideoSeekOptimizer(coroutineScope = coroutineScope)
    private val closed = AtomicBoolean(false)
    private val nextId = AtomicLong(1)
    private val startMutex = Mutex()
    private var serverSocket: ServerSocket? = null
    private var acceptJob: Job? = null

    val baseUrl: String
        get() = "http://127.0.0.1:${serverSocket?.localPort ?: error("proxy not started")}"
    suspend fun start() {
        startMutex.withLock {
            if (serverSocket != null) return
            val socket = withContext(Dispatchers.IO) {
                bindPort()
            }
            serverSocket = socket
            acceptJob = coroutineScope.launch(Dispatchers.IO) { acceptLoop(socket) }
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
        val streamId = nextId.getAndIncrement().toString()
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
        return "$baseUrl/stream/$streamId/${request.displayName.toUrlPathSegment()}"
    }

    fun unregister(streamId: String): Boolean {
        val removed = registry.remove(streamId) != null
        seekOptimizer.removeStream(streamId)
        return removed
    }

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
            val client = runCatching { socket.accept() }.getOrNull() ?: break
            coroutineScope.launch(Dispatchers.IO) {
                try {
                    handleConnection(client)
                } catch (error: CancellationException) {
                    throw error
                } catch (error: IOException) {
                    logClientDisconnect(error)
                } catch (error: Exception) {
                    logConnectionFailure(error)
                } finally {
                    client.close()
                }
            }
        }
    }

    private suspend fun handleConnection(socket: Socket) {
        socket.soTimeout = requestHeaderTimeoutMillis
        val headerBlock = try {
            readRequestHeader(socket) ?: return
        } catch (_: RequestHeaderTooLarge) {
            writeResponse(socket.getOutputStream(), 431, emptyMap(), null)
            return
        }
        val lines = headerBlock.lineSequence().toList()
        val requestLine = lines.firstOrNull().orEmpty()
        val headers = mutableMapOf<String, String>()
        lines.drop(1).forEach { line ->
            if (line.isEmpty()) return@forEach
            val separator = line.indexOf(':')
            if (separator > 0) {
                headers[line.substring(0, separator).trim().lowercase()] = line.substring(separator + 1).trim()
            }
        }
        val parts = requestLine.split(' ', limit = 3)
        if (parts.size < 2) return
        val method = parts[0]
        val path = parts[1]
        if (!path.startsWith("/stream/")) {
            writeResponse(socket.getOutputStream(), 404, emptyMap(), null)
            return
        }
        val streamId = URLDecoder.decode(
            path.removePrefix("/stream/").substringBefore('/').substringBefore('?'),
            Charsets.UTF_8.name(),
        )
        val entry = registry.get(streamId) ?: run {
            writeResponse(socket.getOutputStream(), 404, emptyMap(), null)
            return
        }
        when (method) {
            "HEAD" -> handleHead(socket.getOutputStream(), entry)
            "GET" -> handleGet(socket.getOutputStream(), headers["range"], entry)
            else -> writeResponse(socket.getOutputStream(), 405, emptyMap(), null)
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

    private suspend fun handleHead(output: OutputStream, entry: RegisteredVideoStream) {
        val request = entry.request
        val info = runCatchingCancellable {
            val client = entry.openClient() ?: return@runCatchingCancellable null
            request.size?.let {
                RemoteFileInfo(request.remotePath, it, request.etag, request.lastModified, true)
            } ?: client.head(request.remotePath)
        }.getOrElse { error ->
            logProxyFailure("HEAD metadata", request, error)
            writeResponse(output, 502, emptyMap(), null)
            return
        } ?: run {
            writeResponse(output, 404, emptyMap(), null)
            return
        }
        writeResponse(
            output,
            200,
            mapOf(
                "Content-Length" to info.size.toString(),
                "Content-Type" to (request.mimeType ?: "application/octet-stream"),
                "Accept-Ranges" to "bytes",
            ),
            null,
        )
    }

    private suspend fun handleGet(output: OutputStream, rangeHeader: String?, entry: RegisteredVideoStream) {
        val request = entry.request
        val client = entry.openClient() ?: run {
            writeResponse(output, 404, emptyMap(), null)
            return
        }
        val info = runCatchingCancellable {
            request.size?.let {
                RemoteFileInfo(request.remotePath, it, request.etag, request.lastModified, true)
            } ?: client.head(request.remotePath)
        }.getOrElse { error ->
            logProxyFailure("GET metadata", request, error)
            writeResponse(output, 502, emptyMap(), null)
            return
        }
        val rangeResult = parseRange(rangeHeader, info.size)
        if (rangeResult is ParsedRange.Invalid) {
            writeResponse(output, 416, mapOf("Content-Range" to "bytes */${info.size}"), null)
            return
        }
        val range = when (rangeResult) {
            is ParsedRange.Valid -> rangeResult
            null -> null
            ParsedRange.Invalid -> null
        }
        val response = try {
            if (range == null) {
                client.openFullStream(request.remotePath)
            } else if (shouldUseSeekOptimizer(request, range)) {
                runCatchingCancellable {
                    seekOptimizer.openRangeStream(
                        client = client,
                        request = request,
                        totalSize = info.size,
                        start = range.start,
                        endInclusive = range.endInclusive,
                        settings = request.proxySettings,
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
                    )
                }
            } else {
                client.openRangeStream(
                    path = request.remotePath,
                    start = range.start,
                    endInclusive = range.endInclusive,
                )
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            logProxyFailure("GET stream", request, error)
            writeResponse(output, 502, emptyMap(), null)
            return
        }
        val responseCloseable = Closeable { response.close() }
        if (!registry.addActive(request.streamId, responseCloseable)) {
            writeResponse(output, 404, emptyMap(), null)
            return
        }
        try {
            val statusCode = if (range == null) 200 else 206
            if (range == null && !isCompleteFullBodyResponse(response, info.size)) {
                logProxyFailure(
                    "GET full-body validation",
                    request,
                    IOException("Remote full-body range did not match known size ${info.size}"),
                )
                writeResponse(output, 502, emptyMap(), null)
                return
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
            val responseContentLength = if (statusCode == 200 && response.contentLength < 0) {
                info.size
            } else {
                response.contentLength
            }
            val headers = linkedMapOf(
                "Content-Length" to responseContentLength.toString(),
                "Content-Type" to (response.contentType ?: request.mimeType ?: "application/octet-stream"),
                "Accept-Ranges" to "bytes",
            )
            if (contentRange != null) {
                headers["Content-Range"] = "bytes ${contentRange.start}-${contentRange.endInclusive}/${contentRange.totalSize}"
            }
            writeResponse(
                output,
                statusCode,
                headers,
                response.stream,
            )
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

    private fun writeResponse(output: OutputStream, code: Int, headers: Map<String, String>, body: java.io.InputStream?) {
        val reason = when (code) {
            200 -> "OK"
            206 -> "Partial Content"
            404 -> "Not Found"
            405 -> "Method Not Allowed"
            416 -> "Range Not Satisfiable"
            431 -> "Request Header Fields Too Large"
            502 -> "Bad Gateway"
            else -> "OK"
        }
        val builder = StringBuilder().append("HTTP/1.1 $code $reason\r\n")
        headers.forEach { (k, v) -> builder.append("$k: $v\r\n") }
        builder.append("Connection: close\r\n\r\n")
        output.write(builder.toString().toByteArray())
        body?.copyTo(output)
        output.flush()
    }

    private fun ContentRange.withKnownTotalSize(responseTotalSize: Long?, fallbackTotalSize: Long): ContentRange =
        if (totalSize >= 0) {
            this
        } else {
            copy(totalSize = responseTotalSize?.takeIf { it >= 0 } ?: fallbackTotalSize)
        }

    private fun isCompleteFullBodyResponse(response: com.example.comicdav.network.WebDavStreamResponse, totalSize: Long): Boolean {
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

    private fun logConnectionFailure(error: Throwable) {
        System.err.println("Video proxy connection failed: ${error.message ?: error::class.java.simpleName}")
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        acceptJob?.cancel()
        serverSocket?.close()
        seekOptimizer.close()
        registry.close()
    }

    private sealed class ParsedRange {
        data class Valid(
            val start: Long,
            val endInclusive: Long,
            val seekOptimizationEligible: Boolean,
        ) : ParsedRange() {
            val byteCount: Long
                get() = endInclusive - start + 1L
        }
        data object Invalid : ParsedRange()
    }

    private class RequestHeaderTooLarge : IOException()

    companion object {
        private const val LOOPBACK_HOST = "127.0.0.1"
        private const val DEFAULT_STREAM_CHUNK_BYTES = 8L * 1024L * 1024L
        private const val OPTIMIZER_SEGMENT_BYTES = VideoRangeMemoryCache.DEFAULT_SEGMENT_BYTES
        private const val MAX_OPTIMIZED_RESPONSE_BYTES = OPTIMIZER_SEGMENT_BYTES
        private const val DEFAULT_REQUEST_HEADER_TIMEOUT_MILLIS = 10_000
        private const val DEFAULT_MAX_REQUEST_HEADER_BYTES = 16 * 1024
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
