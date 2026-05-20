package com.example.comicdav.video.proxy

import com.example.comicdav.network.ContentRange
import com.example.comicdav.network.RemoteFileInfo
import com.example.comicdav.network.WebDavClient
import com.example.comicdav.video.WebDavVideoOpenRequest
import java.io.Closeable
import java.io.IOException
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MuBoxVideoProxy(
    private val clientProvider: suspend (String) -> WebDavClient?,
    private val coroutineScope: CoroutineScope,
    private val portRange: IntRange = 49152..65535,
) : Closeable {
    private val registry = StreamRegistry()
    private val closed = AtomicBoolean(false)
    private val nextId = AtomicLong(1)
    private var serverSocket: ServerSocket? = null
    private var acceptJob: Job? = null

    val baseUrl: String
        get() = "http://127.0.0.1:${serverSocket?.localPort ?: error("proxy not started")}"
    suspend fun start() {
        if (serverSocket != null) return
        withContext(Dispatchers.IO) {
            val socket = bindPort()
            serverSocket = socket
            acceptJob = coroutineScope.launch(Dispatchers.IO) { acceptLoop(socket) }
        }
    }

    fun register(request: WebDavVideoOpenRequest): String {
        val streamId = nextId.getAndIncrement().toString()
        registry.put(
            streamId,
            VideoStreamRequest(
                streamId = streamId,
                accountId = request.accountId,
                remotePath = request.remotePath,
                displayName = request.displayName,
                size = request.size,
                etag = request.etag,
                lastModified = request.lastModified,
                mimeType = request.mimeType,
            ),
        )
        return "$baseUrl/stream/$streamId"
    }

    fun unregister(streamId: String) {
        registry.remove(streamId)
    }

    private fun bindPort(): ServerSocket {
        var lastError: IOException? = null
        for (port in portRange) {
            try {
                return ServerSocket().apply {
                    bind(InetSocketAddress(InetAddress.getByName(LOOPBACK_HOST), port), 50)
                }
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
                } finally {
                    client.close()
                }
            }
        }
    }

    private suspend fun handleConnection(socket: Socket) {
        socket.getInputStream().bufferedReader().use { reader ->
            val requestLine = reader.readLine() ?: return
            val headers = mutableMapOf<String, String>()
            while (true) {
                val line = reader.readLine().orEmpty()
                if (line.isEmpty()) break
                val separator = line.indexOf(':')
                if (separator > 0) {
                    headers[line.substring(0, separator).trim().lowercase()] = line.substring(separator + 1).trim()
                }
            }
            val parts = requestLine.split(' ')
            if (parts.size < 2) return
            val method = parts[0]
            val path = parts[1]
            if (!path.startsWith("/stream/")) {
                writeResponse(socket.getOutputStream(), 404, emptyMap(), null)
                return
            }
            val streamId = URLDecoder.decode(path.removePrefix("/stream/"), Charsets.UTF_8.name())
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
    }

    private suspend fun handleHead(output: OutputStream, entry: VideoStreamRequest) {
        val info = runCatchingCancellable {
            val client = clientProvider(entry.accountId) ?: return@runCatchingCancellable null
            entry.size?.let {
                RemoteFileInfo(entry.remotePath, it, entry.etag, entry.lastModified, true)
            } ?: client.head(entry.remotePath)
        }.getOrElse { error ->
            logProxyFailure("HEAD metadata", entry, error)
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
                "Content-Type" to (entry.mimeType ?: "application/octet-stream"),
                "Accept-Ranges" to "bytes",
            ),
            null,
        )
    }

    private suspend fun handleGet(output: OutputStream, rangeHeader: String?, entry: VideoStreamRequest) {
        val client = clientProvider(entry.accountId) ?: run {
            writeResponse(output, 404, emptyMap(), null)
            return
        }
        val info = runCatchingCancellable {
            entry.size?.let {
                RemoteFileInfo(entry.remotePath, it, entry.etag, entry.lastModified, true)
            } ?: client.head(entry.remotePath)
        }.getOrElse { error ->
            logProxyFailure("GET metadata", entry, error)
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
            client.openRangeStream(entry.remotePath, range?.start ?: 0L, range?.endInclusive)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            logProxyFailure("GET stream", entry, error)
            writeResponse(output, 502, emptyMap(), null)
            return
        }
        try {
            val statusCode = if (range == null) 200 else 206
            val contentRange = if (statusCode == 206) {
                response.contentRange ?: ContentRange(
                    start = range!!.start,
                    endInclusive = range.endInclusive,
                    totalSize = response.totalSize ?: info.size,
                )
            } else {
                null
            }
            val headers = linkedMapOf(
                "Content-Length" to response.contentLength.toString(),
                "Content-Type" to (response.contentType ?: entry.mimeType ?: "application/octet-stream"),
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
            response.close()
        }
    }

    private fun parseRange(rangeHeader: String?, totalSize: Long): ParsedRange? {
        val value = rangeHeader ?: return null
        val match = RANGE_REGEX.matchEntire(value.trim()) ?: return ParsedRange.Invalid
        val start = match.groupValues[1].toLongOrNull() ?: return ParsedRange.Invalid
        val end = match.groupValues[2].takeIf { it.isNotBlank() }?.toLongOrNull()
        if (start < 0 || start >= totalSize) return ParsedRange.Invalid
        if (end != null && end < start) return ParsedRange.Invalid
        val boundedEnd = (end ?: (start + DEFAULT_STREAM_CHUNK_BYTES - 1)).coerceAtMost(totalSize - 1)
        return ParsedRange.Valid(start = start, endInclusive = boundedEnd)
    }

    private fun writeResponse(output: OutputStream, code: Int, headers: Map<String, String>, body: java.io.InputStream?) {
        val reason = when (code) {
            200 -> "OK"
            206 -> "Partial Content"
            404 -> "Not Found"
            405 -> "Method Not Allowed"
            416 -> "Range Not Satisfiable"
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

    private suspend inline fun <T> runCatchingCancellable(crossinline block: suspend () -> T): Result<T> =
        try {
            Result.success(block())
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            Result.failure(error)
        }

    private fun logProxyFailure(operation: String, entry: VideoStreamRequest, error: Throwable) {
        System.err.println(
            "Video proxy $operation failed accountId=${entry.accountId} path=${entry.remotePath}: " +
                (error.message ?: error::class.java.simpleName),
        )
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        acceptJob?.cancel()
        serverSocket?.close()
        registry.close()
    }

    private sealed class ParsedRange {
        data class Valid(val start: Long, val endInclusive: Long) : ParsedRange()
        data object Invalid : ParsedRange()
    }

    companion object {
        private const val LOOPBACK_HOST = "127.0.0.1"
        private const val DEFAULT_STREAM_CHUNK_BYTES = 8L * 1024L * 1024L
        private val RANGE_REGEX = Regex("bytes=(\\d+)-(\\d*)")
    }
}
