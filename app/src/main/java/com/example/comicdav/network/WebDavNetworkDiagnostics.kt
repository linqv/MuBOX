package com.example.comicdav.network

import com.example.comicdav.feature.reader.ReaderDiagnosticLog
import com.example.comicdav.feature.reader.ReaderLogCategory
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Proxy
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import okhttp3.Call
import okhttp3.EventListener
import okhttp3.Handshake
import okhttp3.HttpUrl
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response

enum class WebDavOperation {
    PROPFIND,
    HEAD,
    RANGE_GET,
    FULL_GET,
    DOWNLOAD,
}

class WebDavRequestTag(
    val operation: WebDavOperation,
    val path: String,
    val rangeHeader: String?,
) {
    internal val timings = WebDavNetworkTiming()
    private val failureLogged = AtomicBoolean(false)
    private val pathIdLock = Any()
    private var cachedPathId: String? = null

    internal fun markFailureLogged(): Boolean = failureLogged.compareAndSet(false, true)

    internal fun pathIdFor(url: HttpUrl, buildPathId: (HttpUrl) -> String): String {
        cachedPathId?.let { return it }
        return synchronized(pathIdLock) {
            cachedPathId ?: buildPathId(url).also { cachedPathId = it }
        }
    }
}

class WebDavNetworkDiagnostics(
    private val logDetail: ((() -> String) -> Unit) = { event ->
        ReaderDiagnosticLog.detail(ReaderLogCategory.WEBDAV_NETWORK, event)
    },
    private val logFailure: (String, Throwable?) -> Unit = { message, error ->
        if (error != null) {
            ReaderDiagnosticLog.error(ReaderLogCategory.WEBDAV_NETWORK, message, error)
        } else {
            ReaderDiagnosticLog.summary(ReaderLogCategory.WEBDAV_NETWORK) { message }
        }
    },
) {
    fun requestTag(
        operation: WebDavOperation,
        path: String,
        rangeHeader: String? = null,
    ): WebDavRequestTag = WebDavRequestTag(operation, path, rangeHeader)

    fun eventListenerFactory(): EventListener.Factory =
        EventListener.Factory { call ->
            WebDavNetworkEventListener(
                tag = call.request().tag(WebDavRequestTag::class.java),
                diagnostics = this,
            )
        }

    fun logFailure(request: Request, error: Throwable?) {
        val tag = request.tag(WebDavRequestTag::class.java)
        if (tag != null && !tag.markFailureLogged()) return
        logFailure(formatEvent(tag, request, event = "failure", includeCurl = true), error)
    }

    fun sanitizedUrl(url: HttpUrl): String = sanitizeUrl(url).toString()

    private fun logComplete(tag: WebDavRequestTag, request: Request) {
        logDetail { formatEvent(tag, request, event = "complete", includeCurl = false) }
    }

    private fun formatEvent(
        tag: WebDavRequestTag?,
        request: Request,
        event: String,
        includeCurl: Boolean,
    ): String {
        val timings = tag?.timings?.snapshot()
        return buildString {
            append("event=")
            append(event)
            append(" operation=")
            append(tag?.operation ?: request.method)
            append(" scheme=")
            append(request.url.scheme)
            append(" host=")
            append(request.url.host)
            append(" port=")
            append(request.url.port)
            append(" pathId=")
            append(
                tag?.pathIdFor(request.url) { url ->
                    "webdav:${shortHash(sanitizedUrl(url))}"
                } ?: "webdav:${shortHash(sanitizedUrl(request.url))}",
            )
            pathExtension(tag?.path)?.let { extension ->
                append(" pathExt=")
                append(extension)
            }
            append(" range=")
            append(tag?.rangeHeader ?: request.header("Range") ?: "none")
            append(" code=")
            append(timings?.responseCode ?: -1)
            append(" dnsMs=")
            append(timings?.dnsMs.formatMs())
            append(" connectMs=")
            append(timings?.connectMs.formatMs())
            append(" tlsMs=")
            append(timings?.tlsMs.formatMs())
            append(" responseMs=")
            append(timings?.responseHeadersMs.formatMs())
            append(" bodyMs=")
            append(timings?.responseBodyMs.formatMs())
            append(" totalMs=")
            append(timings?.totalMs.formatMs())
            if (includeCurl) {
                append(" curl=")
                append(sanitizedCurl(request))
            }
        }
    }

    private fun sanitizedCurl(request: Request): String =
        request.newBuilder()
            .removeHeader("Authorization")
            .removeHeader("Proxy-Authorization")
            .removeHeader("Cookie")
            .url(sanitizeUrl(request.url))
            .build()
            .toCurl()

    private fun sanitizeUrl(url: HttpUrl): HttpUrl {
        val builder = url.newBuilder()
            .username("")
            .password("")
        url.queryParameterNames.forEach { name ->
            if (isSensitiveQueryName(name)) {
                builder.setQueryParameter(name, "<redacted>")
            }
        }
        return builder.build()
    }

    private fun isSensitiveQueryName(name: String): Boolean =
        SENSITIVE_QUERY_NAME.containsMatchIn(name)

    private fun pathExtension(path: String?): String? {
        val name = path
            ?.substringBefore('?')
            ?.substringAfterLast('/')
            ?.substringAfterLast('.', missingDelimiterValue = "")
            ?.lowercase(Locale.ROOT)
            .orEmpty()
        return name.takeIf { it.isNotBlank() && it.length <= 12 }
    }

    private class WebDavNetworkEventListener(
        private val tag: WebDavRequestTag?,
        private val diagnostics: WebDavNetworkDiagnostics,
    ) : EventListener() {
        override fun callStart(call: Call) {
            tag?.timings?.callStart()
        }

        override fun dnsStart(call: Call, domainName: String) {
            tag?.timings?.start(Stage.DNS)
        }

        override fun dnsEnd(call: Call, domainName: String, inetAddressList: List<java.net.InetAddress>) {
            tag?.timings?.end(Stage.DNS)
        }

        override fun connectStart(call: Call, inetSocketAddress: InetSocketAddress, proxy: Proxy) {
            tag?.timings?.start(Stage.CONNECT)
        }

        override fun connectEnd(call: Call, inetSocketAddress: InetSocketAddress, proxy: Proxy, protocol: Protocol?) {
            tag?.timings?.end(Stage.CONNECT)
        }

        override fun connectFailed(
            call: Call,
            inetSocketAddress: InetSocketAddress,
            proxy: Proxy,
            protocol: Protocol?,
            ioe: IOException,
        ) {
            tag?.timings?.end(Stage.CONNECT)
        }

        override fun secureConnectStart(call: Call) {
            tag?.timings?.start(Stage.TLS)
        }

        override fun secureConnectEnd(call: Call, handshake: Handshake?) {
            tag?.timings?.end(Stage.TLS)
        }

        override fun requestHeadersStart(call: Call) {
            tag?.timings?.start(Stage.REQUEST_HEADERS)
        }

        override fun requestHeadersEnd(call: Call, request: Request) {
            tag?.timings?.end(Stage.REQUEST_HEADERS)
        }

        override fun requestBodyStart(call: Call) {
            tag?.timings?.start(Stage.REQUEST_BODY)
        }

        override fun requestBodyEnd(call: Call, byteCount: Long) {
            tag?.timings?.end(Stage.REQUEST_BODY)
        }

        override fun requestFailed(call: Call, ioe: IOException) {
            tag?.timings?.end(Stage.REQUEST_BODY)
            logFailureUnlessCanceled(call, ioe)
        }

        override fun responseHeadersStart(call: Call) {
            tag?.timings?.start(Stage.RESPONSE_HEADERS)
        }

        override fun responseHeadersEnd(call: Call, response: Response) {
            tag?.timings?.end(Stage.RESPONSE_HEADERS)
            tag?.timings?.responseCode(response.code)
        }

        override fun responseBodyStart(call: Call) {
            tag?.timings?.start(Stage.RESPONSE_BODY)
        }

        override fun responseBodyEnd(call: Call, byteCount: Long) {
            tag?.timings?.end(Stage.RESPONSE_BODY)
        }

        override fun responseFailed(call: Call, ioe: IOException) {
            tag?.timings?.end(Stage.RESPONSE_BODY)
            logFailureUnlessCanceled(call, ioe)
        }

        override fun callEnd(call: Call) {
            val currentTag = tag ?: return
            currentTag.timings.callEnd()
            diagnostics.logComplete(currentTag, call.request())
        }

        override fun callFailed(call: Call, ioe: IOException) {
            tag?.timings?.callEnd()
            logFailureUnlessCanceled(call, ioe)
        }

        private fun logFailureUnlessCanceled(call: Call, ioe: IOException) {
            if (call.isCanceled()) return
            diagnostics.logFailure(call.request(), ioe)
        }
    }

    private companion object {
        private val SENSITIVE_QUERY_NAME =
            Regex(
                "(?i)(^|[_-])" +
                    "(password|passwd|token|access[_-]?token|refresh[_-]?token|secret|api[_-]?key|key|signature|sig|credential)" +
                    "($|[_-])",
            )
    }
}

internal enum class Stage {
    DNS,
    CONNECT,
    TLS,
    REQUEST_HEADERS,
    REQUEST_BODY,
    RESPONSE_HEADERS,
    RESPONSE_BODY,
}

internal data class WebDavNetworkTimingSnapshot(
    val dnsMs: Long?,
    val connectMs: Long?,
    val tlsMs: Long?,
    val responseHeadersMs: Long?,
    val responseBodyMs: Long?,
    val totalMs: Long?,
    val responseCode: Int?,
)

internal class WebDavNetworkTiming {
    private val lock = Any()
    private var callStartNs: Long? = null
    private var callEndNs: Long? = null
    private var responseCode: Int? = null
    private val stages = Stage.entries.associateWith { StageTiming() }

    fun callStart(nowNs: Long = System.nanoTime()) {
        synchronized(lock) {
            callStartNs = nowNs
        }
    }

    fun callEnd(nowNs: Long = System.nanoTime()) {
        synchronized(lock) {
            callEndNs = nowNs
        }
    }

    fun start(stage: Stage, nowNs: Long = System.nanoTime()) {
        synchronized(lock) {
            stages.getValue(stage).start(nowNs)
        }
    }

    fun end(stage: Stage, nowNs: Long = System.nanoTime()) {
        synchronized(lock) {
            stages.getValue(stage).end(nowNs)
        }
    }

    fun responseCode(code: Int) {
        synchronized(lock) {
            responseCode = code
        }
    }

    fun snapshot(nowNs: Long = System.nanoTime()): WebDavNetworkTimingSnapshot =
        synchronized(lock) {
            val start = callStartNs
            val end = callEndNs ?: nowNs
            WebDavNetworkTimingSnapshot(
                dnsMs = stages.getValue(Stage.DNS).elapsedMs(),
                connectMs = stages.getValue(Stage.CONNECT).elapsedMs(),
                tlsMs = stages.getValue(Stage.TLS).elapsedMs(),
                responseHeadersMs = stages.getValue(Stage.RESPONSE_HEADERS).elapsedMs(),
                responseBodyMs = stages.getValue(Stage.RESPONSE_BODY).elapsedMs(),
                totalMs = if (start != null) (end - start).coerceAtLeast(0L).nanosToMillis() else null,
                responseCode = responseCode,
            )
        }
}

private class StageTiming {
    private var startedAtNs: Long? = null
    private var elapsedNs: Long = 0L
    private var count: Int = 0

    fun start(nowNs: Long) {
        startedAtNs = nowNs
    }

    fun end(nowNs: Long) {
        val start = startedAtNs ?: return
        elapsedNs += (nowNs - start).coerceAtLeast(0L)
        startedAtNs = null
        count += 1
    }

    fun elapsedMs(): Long? =
        if (count == 0) {
            null
        } else {
            elapsedNs.nanosToMillis()
        }
}

private fun Long.nanosToMillis(): Long =
    TimeUnit.NANOSECONDS.toMillis(this)

private fun Long?.formatMs(): String = this?.toString() ?: "-"

private fun shortHash(raw: String): String {
    val digest = MessageDigest.getInstance("SHA-256")
        .digest(raw.toByteArray(Charsets.UTF_8))
    return digest.take(6).joinToString("") { byte -> "%02x".format(byte) }
}
