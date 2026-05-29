package com.example.comicdav.network

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Credentials
import okhttp3.EventListener
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.net.URI
import java.net.URLDecoder
import java.util.Locale

class OkHttpWebDavClient(
    private val baseUrl: String,
    private val username: String?,
    private val password: String?,
    httpClient: OkHttpClient = HttpClients.webDav,
    private val diagnostics: WebDavNetworkDiagnostics = WebDavNetworkDiagnostics(),
) : WebDavClient {
    private val httpClient: OkHttpClient = run {
        val upstreamEvents = httpClient.eventListenerFactory
        val diagnosticEvents = diagnostics.eventListenerFactory()
        httpClient.newBuilder()
            .eventListenerFactory(
                EventListener.Factory { call ->
                    upstreamEvents.create(call).plus(diagnosticEvents.create(call))
                },
            )
            .build()
    }

    override suspend fun list(path: String): List<WebDavItem> = withContext(Dispatchers.IO) {
        val body = """<?xml version="1.0" encoding="utf-8" ?>
            <d:propfind xmlns:d="DAV:">
                <d:prop>
                    <d:resourcetype />
                    <d:getcontentlength />
                    <d:getetag />
                    <d:getlastmodified />
                </d:prop>
            </d:propfind>
        """.trimIndent().toRequestBody("application/xml; charset=utf-8".toMediaType())
        val request = requestBuilder(path, WebDavOperation.PROPFIND)
            .method("PROPFIND", body)
            .header("Depth", "1")
            .build()
        request.withFailureDiagnostics {
            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    throw WebDavException.HttpStatus(
                        response.code,
                        httpFailureMessage("PROPFIND", response.code, request),
                    )
                }
                WebDavXmlParser.parse(response.body.byteStream(), request.url.encodedPath)
            }
        }
    }

    override suspend fun head(path: String): RemoteFileInfo = withContext(Dispatchers.IO) {
        val request = requestBuilder(path, WebDavOperation.HEAD).head().build()
        request.withFailureDiagnostics {
            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    throw WebDavException.HttpStatus(
                        response.code,
                        httpFailureMessage("HEAD", response.code, request),
                    )
                }
                val size = response.header("Content-Length")?.toLongOrNull()
                    ?: throw WebDavException.MissingMetadata("HEAD response is missing Content-Length")
                RemoteFileInfo(
                    path = path,
                    size = size,
                    etag = response.header("ETag"),
                    lastModified = response.header("Last-Modified")?.hashCode()?.toLong(),
                    supportsRange = response.header("Accept-Ranges")?.equals("bytes", ignoreCase = true) == true,
                )
            }
        }
    }

    override suspend fun readRange(path: String, start: Long, endInclusive: Long): ByteArray =
        openRangeStream(path, start, endInclusive).useResponse { response ->
            response.stream.readBytes()
        }

    override suspend fun openRangeStream(
        path: String,
        start: Long,
        endInclusive: Long?,
    ): WebDavStreamResponse =
        openRangeStream(path, start, endInclusive, registerCancellation = {})

    override suspend fun openRangeStream(
        path: String,
        start: Long,
        endInclusive: Long?,
        registerCancellation: (Closeable) -> Unit,
    ): WebDavStreamResponse = withContext(Dispatchers.IO) {
        executeRangeStreamWithRetries(
            path = path,
            start = start,
            endInclusive = endInclusive,
            registerCancellation = registerCancellation,
        )
    }

    private suspend fun executeRangeStreamWithRetries(
        path: String,
        start: Long,
        endInclusive: Long?,
        registerCancellation: (Closeable) -> Unit,
    ): WebDavStreamResponse {
        val rangeHeader = buildRangeHeader(start, endInclusive)
        var retryIndex = 0
        while (true) {
            val request = requestBuilder(path, WebDavOperation.RANGE_GET, rangeHeader)
                .get()
                .header("Range", rangeHeader)
                .build()
            val call = httpClient.newCall(request)
            registerCancellation(Closeable { call.cancel() })
            try {
                return executeRangeStream(
                    call = call,
                    request = request,
                    start = start,
                    endInclusive = endInclusive,
                )
            } catch (error: Throwable) {
                if (call.isCanceled()) {
                    throw CancellationException("range request cancelled").also { it.initCause(error) }
                }
                val retryDelayMs = rangeRetryDelayMs(error, retryIndex)
                if (retryDelayMs == null) {
                    diagnostics.logFailure(request, error)
                    throw error
                }
                retryIndex++
                delay(retryDelayMs)
            }
        }
    }

    override suspend fun openFullStream(path: String): WebDavStreamResponse =
        openFullStream(path, registerCancellation = {})

    override suspend fun openFullStream(
        path: String,
        registerCancellation: (Closeable) -> Unit,
    ): WebDavStreamResponse = withContext(Dispatchers.IO) {
        val request = requestBuilder(path, WebDavOperation.FULL_GET)
            .get()
            .build()
        request.withFailureDiagnostics {
            val call = httpClient.newCall(request)
            registerCancellation(Closeable { call.cancel() })
            val response = call.execute()
            if (response.code != 200) {
                response.close()
                throw WebDavException.HttpStatus(
                    response.code,
                    httpFailureMessage("GET", response.code, request),
                )
            }
            val body = response.body
            val contentLength = body.contentLength()
            val stream = body.byteStream()
            WebDavStreamResponse(
                stream = stream,
                statusCode = response.code,
                contentLength = contentLength,
                contentRange = null,
                contentType = response.header("Content-Type"),
                totalSize = contentLength.takeIf { it >= 0 },
                close = { response.close() },
            )
        }
    }

    override suspend fun download(path: String, target: File, onBytesRead: (Long) -> Unit): Long =
        withContext(Dispatchers.IO) {
            val request = requestBuilder(path, WebDavOperation.DOWNLOAD).get().build()
            request.withFailureDiagnostics {
                httpClient.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        throw WebDavException.HttpStatus(
                            response.code,
                            httpFailureMessage("GET", response.code, request),
                        )
                    }
                    val body = response.body
                    target.parentFile?.mkdirs()
                    var total = 0L
                    body.byteStream().use { input ->
                        target.outputStream().use { output ->
                            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                            while (true) {
                                val read = input.read(buffer)
                                if (read == -1) break
                                output.write(buffer, 0, read)
                                total += read
                                onBytesRead(total)
                            }
                        }
                    }
                    total
                }
            }
        }

    private fun requestBuilder(
        path: String,
        operation: WebDavOperation,
        rangeHeader: String? = null,
    ): Request.Builder =
        Request.Builder()
            .url(resolveUrl(path))
            .tag(WebDavRequestTag::class.java, diagnostics.requestTag(operation, path, rangeHeader))
            .also { builder ->
                if (!username.isNullOrBlank() && password != null) {
                    builder.header("Authorization", Credentials.basic(username, password))
                }
            }

    private fun resolveUrl(path: String): String {
        if (path.startsWith("http://", ignoreCase = true) || path.startsWith("https://", ignoreCase = true)) {
            return path
        }

        val base = if (baseUrl.endsWith("/")) baseUrl else "$baseUrl/"
        val baseUri = URI(base)
        val basePath = baseUri.rawPath.orEmpty()
        val mountedPrefix = if (basePath.endsWith("/")) basePath else "$basePath/"
        val decodedMountedPrefix = decodePath(mountedPrefix)
        val encodedMountedPrefix = encodePath(decodedMountedPrefix)
        val requestPath = normalizeRequestPath(
            path = path,
            mountedPrefixes = listOf(mountedPrefix, decodedMountedPrefix, encodedMountedPrefix),
        )
        return URI(base).resolve(requestPath).toString()
    }

    private fun normalizeRequestPath(
        path: String,
        mountedPrefixes: List<String>,
    ): String {
        if (mountedPrefixes.any { path.startsWith(it) }) {
            return path
        }
        if (mountedPrefixes.any { path.startsWith(it.trimStart('/')) }) {
            return "/$path"
        }
        return path.trimStart('/')
    }

    private fun decodePath(path: String): String =
        URLDecoder.decode(path, Charsets.UTF_8.name())

    private fun encodePath(path: String): String =
        URI(null, null, path, null).toASCIIString()

    private fun buildRangeHeader(start: Long, endInclusive: Long?): String =
        if (endInclusive == null) {
            "bytes=$start-"
        } else {
            "bytes=$start-$endInclusive"
        }

    private fun httpFailureMessage(operation: String, statusCode: Int, request: Request): String =
        "$operation failed with HTTP $statusCode: ${diagnostics.sanitizedUrl(request.url)}"

    private fun executeRangeStream(
        call: Call,
        request: Request,
        start: Long,
        endInclusive: Long?,
    ): WebDavStreamResponse {
        val response = call.execute()
        when (response.code) {
            206 -> {
                val body = response.body
                try {
                    val contentRangeHeader = response.header("Content-Range")
                    val parsedRange = validateContentRange(
                        header = contentRangeHeader,
                        expectedStart = start,
                        expectedEndInclusive = endInclusive,
                    )
                    val totalSize = parsedRange.totalSize.takeIf { it >= 0 }
                    val expectedContentLength = parsedRange.endInclusive - parsedRange.start + 1
                    val declaredContentLength = body.contentLength()
                    if (declaredContentLength >= 0 && declaredContentLength != expectedContentLength) {
                        throw WebDavException.InvalidContentRange(
                            "Expected response body length $expectedContentLength but got $declaredContentLength",
                        )
                    }
                    val contentLength = declaredContentLength.takeIf { it >= 0 } ?: expectedContentLength
                    val stream = body.byteStream()
                    val close = { response.close() }
                    return WebDavStreamResponse(
                        stream = stream,
                        statusCode = response.code,
                        contentLength = contentLength,
                        contentRange = parsedRange,
                        contentType = response.header("Content-Type"),
                        totalSize = totalSize,
                        close = close,
                    )
                } catch (error: Throwable) {
                    response.close()
                    throw error
                }
            }
            200 -> {
                response.close()
                throw WebDavException.RangeNotSupported()
            }
            else -> {
                response.close()
                throw WebDavException.HttpStatus(
                    response.code,
                    httpFailureMessage("Range GET", response.code, request),
                )
            }
        }
    }

    private fun rangeRetryDelayMs(error: Throwable, retryIndex: Int): Long? {
        if (retryIndex >= RANGE_RETRY_DELAYS_MS.size || error is CancellationException) return null
        val statusCode = (error as? WebDavException.HttpStatus)?.statusCode
        if (statusCode in RETRYABLE_RANGE_STATUS_CODES) return RANGE_RETRY_DELAYS_MS[retryIndex]
        if (error is IOException) return RANGE_RETRY_DELAYS_MS[retryIndex]
        return null
    }

    private inline fun <T> Request.withFailureDiagnostics(block: () -> T): T {
        try {
            return block()
        } catch (error: Throwable) {
            diagnostics.logFailure(this, error)
            throw error
        }
    }

    private fun parseContentRange(header: String?): ContentRange? {
        val value = header ?: return null
        val match = CONTENT_RANGE.matchEntire(value.lowercase(Locale.US)) ?: return null
        return ContentRange(
            start = match.groupValues[1].toLong(),
            endInclusive = match.groupValues[2].toLong(),
            totalSize = match.groupValues[3].takeIf { it != "*" }?.toLong() ?: -1L,
        )
    }

    private fun validateContentRange(
        header: String?,
        expectedStart: Long,
        expectedEndInclusive: Long?,
    ): ContentRange {
        val parsed = parseContentRange(header)
            ?: throw WebDavException.InvalidContentRange("Missing or invalid Content-Range header: ${header ?: "<null>"}")
        if (parsed.endInclusive < parsed.start) {
            throw WebDavException.InvalidContentRange(
                "Content-Range end is before start in ${header ?: "<null>"}",
            )
        }
        if (parsed.totalSize >= 0 && parsed.endInclusive >= parsed.totalSize) {
            throw WebDavException.InvalidContentRange(
                "Content-Range end ${parsed.endInclusive} is outside total size ${parsed.totalSize}",
            )
        }
        if (parsed.start != expectedStart) {
            throw WebDavException.InvalidContentRange(
                "Expected Content-Range start $expectedStart but got ${parsed.start} in ${header ?: "<null>"}",
            )
        }
        if (expectedEndInclusive != null && parsed.endInclusive != expectedEndInclusive) {
            throw WebDavException.InvalidContentRange(
                "Expected Content-Range end $expectedEndInclusive but got ${parsed.endInclusive} in ${header ?: "<null>"}",
            )
        }
        return parsed
    }

    private suspend fun <T> WebDavStreamResponse.useResponse(block: suspend (WebDavStreamResponse) -> T): T {
        try {
            return block(this)
        } finally {
            close()
        }
    }

    companion object {
        private val CONTENT_RANGE = Regex("""bytes\s+(\d+)-(\d+)/(\d+|\*)""")
        private val RETRYABLE_RANGE_STATUS_CODES = setOf(429, 500, 502, 503, 504)
        private val RANGE_RETRY_DELAYS_MS = longArrayOf(150L, 400L)
    }
}
