package org.mubox.reader.network

import org.mubox.reader.core.remote.RemoteFileInfo
import org.mubox.reader.core.remote.WebDavClient
import org.mubox.reader.core.remote.WebDavException
import org.mubox.reader.core.remote.WebDavItem
import org.mubox.reader.core.remote.WebDavStreamResponse
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Credentials
import okhttp3.EventListener
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

internal class OkHttpWebDavClient(
    private val baseUrl: String,
    private val username: String?,
    private val password: String?,
    httpClient: OkHttpClient = HttpClients.webDav,
    private val diagnostics: WebDavNetworkDiagnostics = WebDavNetworkDiagnostics(),
    private val allowPlaintextHttp: Boolean = isPlaintextHttpUrl(baseUrl),
) : WebDavClient {
    private val urlResolver = WebDavUrlResolver(
        baseUrl = baseUrl,
        allowPlaintextHttp = allowPlaintextHttp,
        sanitizedUrl = diagnostics::sanitizedUrl,
    )
    private val httpClient: OkHttpClient = run {
        val upstreamEvents = httpClient.eventListenerFactory
        val diagnosticEvents = diagnostics.eventListenerFactory()
        httpClient.newBuilder()
            .eventListenerFactory(
                EventListener.Factory { call ->
                    upstreamEvents.create(call).plus(diagnosticEvents.create(call))
                },
            )
            .addNetworkInterceptor { chain ->
                urlResolver.requireAllowedTransport(chain.request().url)
                chain.proceed(chain.request())
            }
            .build()
    }

    override suspend fun list(path: String): List<WebDavItem> = withContext(Dispatchers.IO) {
        val body = propfindRequestBody()
        val request = requestBuilder(path, WebDavOperation.PROPFIND)
            .method("PROPFIND", body)
            .header("Depth", "1")
            .build()
        request.withFailureDiagnostics {
            httpClient.withNonStreamingResponse(request) { response ->
                WebDavResponseValidator.requireSuccessful(
                    response.code,
                    "PROPFIND",
                    diagnostics.sanitizedUrl(request.url),
                )
                WebDavXmlParser.parse(response.body.byteStream(), request.url.encodedPath, urlResolver.baseOrigin())
            }
        }
    }

    override suspend fun head(path: String): RemoteFileInfo = withContext(Dispatchers.IO) {
        val request = requestBuilder(path, WebDavOperation.HEAD).head().build()
        val headResult = request.withFailureDiagnostics {
            httpClient.withNonStreamingResponse(request) { response ->
                if (!response.isSuccessful) {
                    if (response.code in HEAD_FALLBACK_STATUS_CODES) {
                        HeadResponseResult.FallbackToPropfind(supportsRange = false)
                    } else {
                        WebDavResponseValidator.requireSuccessful(
                            response.code,
                            "HEAD",
                            diagnostics.sanitizedUrl(request.url),
                        )
                        HeadResponseResult.FallbackToPropfind(supportsRange = false)
                    }
                } else {
                    val supportsRange = response.supportsRange()
                    val size = response.header("Content-Length")?.toLongOrNull()
                    if (size == null) {
                        HeadResponseResult.FallbackToPropfind(supportsRange = supportsRange)
                    } else {
                        HeadResponseResult.Info(
                            RemoteFileInfo(
                                path = path,
                                size = size,
                                etag = response.header("ETag"),
                                lastModified = parseHttpDateMillis(response.header("Last-Modified")),
                                supportsRange = supportsRange,
                            ),
                        )
                    }
                }
            }
        }
        when (headResult) {
            is HeadResponseResult.Info -> headResult.info
            is HeadResponseResult.FallbackToPropfind -> propfindMetadata(path, supportsRange = headResult.supportsRange)
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
                    throw error.asNetworkExceptionIfNeeded()
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
            try {
                WebDavResponseValidator.requireStatus(
                    response.code,
                    expectedStatus = 200,
                    operation = "GET",
                    sanitizedUrl = diagnostics.sanitizedUrl(request.url),
                )
            } catch (error: Throwable) {
                response.close()
                throw error
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
                httpClient.withCancellableResponse(request, callTimeoutSeconds = null) { response, ensureActive ->
                    WebDavResponseValidator.requireSuccessful(
                        response.code,
                        "GET",
                        diagnostics.sanitizedUrl(request.url),
                    )
                    val body = response.body
                    target.parentFile?.mkdirs()
                    var total = 0L
                    var lastReportedTotal = 0L
                    fun reportProgress(force: Boolean = false) {
                        if (force || total - lastReportedTotal >= DOWNLOAD_PROGRESS_STEP_BYTES) {
                            onBytesRead(total)
                            lastReportedTotal = total
                        }
                    }
                    body.byteStream().use { input ->
                        target.outputStream().use { output ->
                            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                            while (true) {
                                ensureActive()
                                val read = input.read(buffer)
                                if (read == -1) break
                                output.write(buffer, 0, read)
                                total += read
                                reportProgress()
                            }
                        }
                    }
                    if (total != lastReportedTotal) {
                        reportProgress(force = true)
                    }
                    total
                }
        }
    }

    private suspend inline fun <T> OkHttpClient.withNonStreamingResponse(
        request: Request,
        crossinline block: (okhttp3.Response) -> T,
    ): T = withCancellableResponse(
        request = request,
        callTimeoutSeconds = NON_STREAMING_CALL_TIMEOUT_SECONDS,
    ) { response, _ -> block(response) }

    private suspend inline fun <T> OkHttpClient.withCancellableResponse(
        request: Request,
        callTimeoutSeconds: Long?,
        crossinline block: (okhttp3.Response, ensureActive: () -> Unit) -> T,
    ): T = suspendCancellableCoroutine { continuation ->
        val call = newCall(request)
        if (callTimeoutSeconds != null) {
            call.timeout().timeout(callTimeoutSeconds, java.util.concurrent.TimeUnit.SECONDS)
        }
        continuation.invokeOnCancellation { call.cancel() }
        val ensureActive = {
            continuation.context.ensureActive()
            if (call.isCanceled()) {
                throw CancellationException("request cancelled")
            }
        }
        try {
            ensureActive()
            val result = call.execute().use { response -> block(response, ensureActive) }
            if (continuation.isActive) {
                continuation.resume(result)
            }
        } catch (error: Throwable) {
            val mapped = if (call.isCanceled() && error !is CancellationException) {
                CancellationException("request cancelled").also { it.initCause(error) }
            } else {
                error
            }
            if (continuation.isActive) {
                continuation.resumeWithException(mapped)
            }
        }
    }

    private fun requestBuilder(
        path: String,
        operation: WebDavOperation,
        rangeHeader: String? = null,
    ): Request.Builder {
        val resolvedUrl = urlResolver.resolve(path)
        return Request.Builder()
            .url(resolvedUrl)
            .tag(WebDavRequestTag::class.java, diagnostics.requestTag(operation, path, rangeHeader))
            .also { builder ->
                if (!username.isNullOrBlank() && password != null) {
                    builder.header("Authorization", Credentials.basic(username, password, Charsets.UTF_8))
                }
            }
    }

    private fun buildRangeHeader(start: Long, endInclusive: Long?): String =
        if (endInclusive == null) {
            "bytes=$start-"
        } else {
            "bytes=$start-$endInclusive"
        }

    private fun executeRangeStream(
        call: Call,
        request: Request,
        start: Long,
        endInclusive: Long?,
    ): WebDavStreamResponse {
        val response = call.execute()
        val body = response.body
        try {
            val validated = WebDavResponseValidator.validateRange(
                statusCode = response.code,
                contentRangeHeader = response.header("Content-Range"),
                declaredContentLength = body.contentLength(),
                expectedStart = start,
                expectedEndInclusive = endInclusive,
                sanitizedUrl = diagnostics.sanitizedUrl(request.url),
            )
            return WebDavStreamResponse(
                stream = body.byteStream(),
                statusCode = response.code,
                contentLength = validated.contentLength,
                contentRange = validated.contentRange,
                contentType = response.header("Content-Type"),
                totalSize = validated.totalSize,
                close = { response.close() },
            )
        } catch (error: Throwable) {
            response.close()
            throw error
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
            throw error.asNetworkExceptionIfNeeded()
        }
    }

    private fun Throwable.asNetworkExceptionIfNeeded(): Throwable =
        when (this) {
            is WebDavException -> this
            is CancellationException -> this
            is IOException -> WebDavException.Network(message ?: "Network request failed", this)
            else -> this
        }

    private fun okhttp3.Response.supportsRange(): Boolean =
        header("Accept-Ranges")?.equals("bytes", ignoreCase = true) == true

    private sealed class HeadResponseResult {
        data class Info(val info: RemoteFileInfo) : HeadResponseResult()
        data class FallbackToPropfind(val supportsRange: Boolean) : HeadResponseResult()
    }

    private suspend fun propfindMetadata(path: String, supportsRange: Boolean): RemoteFileInfo {
        val request = requestBuilder(path, WebDavOperation.PROPFIND)
            .method("PROPFIND", propfindRequestBody())
            .header("Depth", "0")
            .build()
        return request.withFailureDiagnostics {
            httpClient.withNonStreamingResponse(request) { response ->
                WebDavResponseValidator.requireSuccessful(
                    response.code,
                    "PROPFIND",
                    diagnostics.sanitizedUrl(request.url),
                )
                WebDavXmlParser.parseMetadata(response.body.byteStream(), request.url.encodedPath, supportsRange)
                    ?.copy(path = path)
                    ?: throw WebDavException.MissingMetadata("PROPFIND response is missing Content-Length")
            }
        }
    }

    private fun propfindRequestBody() = """<?xml version="1.0" encoding="utf-8" ?>
        <d:propfind xmlns:d="DAV:">
            <d:prop>
                <d:resourcetype />
                <d:getcontentlength />
                <d:getetag />
                <d:getlastmodified />
            </d:prop>
        </d:propfind>
    """.trimIndent().toRequestBody("application/xml; charset=utf-8".toMediaType())

    private suspend fun <T> WebDavStreamResponse.useResponse(block: suspend (WebDavStreamResponse) -> T): T {
        try {
            return block(this)
        } finally {
            close()
        }
    }

    companion object {
        private val RETRYABLE_RANGE_STATUS_CODES = setOf(429, 500, 502, 503, 504)
        private val HEAD_FALLBACK_STATUS_CODES = setOf(405, 501)
        private val RANGE_RETRY_DELAYS_MS = longArrayOf(150L, 400L)
        private const val NON_STREAMING_CALL_TIMEOUT_SECONDS = 30L
        private const val DOWNLOAD_PROGRESS_STEP_BYTES = 256L * 1024L

        private fun isPlaintextHttpUrl(value: String): Boolean =
            value.trim().toHttpUrlOrNull()?.scheme == "http"

        internal fun parseHttpDateMillis(value: String?): Long? =
            value
                ?.takeIf { it.isNotBlank() }
                ?.let { header ->
                    try {
                        ZonedDateTime.parse(header, DateTimeFormatter.RFC_1123_DATE_TIME)
                            .toInstant()
                            .toEpochMilli()
                    } catch (_: DateTimeParseException) {
                        null
                    }
                }
    }

}
