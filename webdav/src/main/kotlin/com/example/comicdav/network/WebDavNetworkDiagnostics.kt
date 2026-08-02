package com.example.comicdav.network

import com.example.comicdav.core.diagnostics.DiagnosticCategory
import com.example.comicdav.core.diagnostics.Diagnostics
import com.example.comicdav.core.diagnostics.NoopDiagnostics
import java.io.IOException
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import okhttp3.Call
import okhttp3.EventListener
import okhttp3.HttpUrl
import okhttp3.Request

internal enum class WebDavOperation {
    PROPFIND,
    HEAD,
    RANGE_GET,
    FULL_GET,
    DOWNLOAD,
}

internal class WebDavRequestTag(
    val operation: WebDavOperation,
    val path: String,
    val rangeHeader: String?,
) {
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

internal class WebDavNetworkDiagnostics(
    private val diagnostics: Diagnostics = NoopDiagnostics,
    private val recordFailure: (String, Throwable?) -> Unit = { message, error ->
        diagnostics.error(DiagnosticCategory.WEBDAV_NETWORK, message, error)
    },
) {
    fun requestTag(
        operation: WebDavOperation,
        path: String,
        rangeHeader: String? = null,
    ): WebDavRequestTag = WebDavRequestTag(operation, path, rangeHeader)

    /** Captures transport exceptions only; development-time phase timing was intentionally removed. */
    fun eventListenerFactory(): EventListener.Factory =
        EventListener.Factory { FailureEventListener(this) }

    fun logFailure(request: Request, error: Throwable?) {
        val tag = request.tag(WebDavRequestTag::class.java)
        if (tag != null && !tag.markFailureLogged()) return
        recordFailure(formatFailure(tag, request), error)
    }

    fun sanitizedUrl(url: HttpUrl): String =
        url.newBuilder()
            .username("")
            .password("")
            .encodedPath("/<redacted>")
            .query(null)
            .fragment(null)
            .build()
            .toString()

    private fun formatFailure(tag: WebDavRequestTag?, request: Request): String = buildString {
        append("webdav_request_failed operation=")
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
                "webdav:${shortHash(resourceIdentity(url))}"
            } ?: "webdav:${shortHash(resourceIdentity(request.url))}",
        )
        pathExtension(tag?.path)?.let { extension ->
            append(" pathExt=")
            append(extension)
        }
        append(" range=")
        append(tag?.rangeHeader ?: request.header("Range") ?: "none")
    }

    private fun resourceIdentity(url: HttpUrl): String =
        url.newBuilder()
            .username("")
            .password("")
            .query(null)
            .fragment(null)
            .build()
            .toString()

    private fun pathExtension(path: String?): String? {
        val name = path
            ?.substringBefore('?')
            ?.substringAfterLast('/')
            ?.substringAfterLast('.', missingDelimiterValue = "")
            ?.lowercase(Locale.ROOT)
            .orEmpty()
        return name.takeIf { it.isNotBlank() && it.length <= 12 }
    }

    private class FailureEventListener(
        private val diagnostics: WebDavNetworkDiagnostics,
    ) : EventListener() {
        override fun requestFailed(call: Call, ioe: IOException) {
            logUnlessCanceled(call, ioe)
        }

        override fun responseFailed(call: Call, ioe: IOException) {
            logUnlessCanceled(call, ioe)
        }

        override fun callFailed(call: Call, ioe: IOException) {
            logUnlessCanceled(call, ioe)
        }

        private fun logUnlessCanceled(call: Call, error: IOException) {
            if (!call.isCanceled()) diagnostics.logFailure(call.request(), error)
        }
    }

}

private fun shortHash(raw: String): String {
    val digest = MessageDigest.getInstance("SHA-256")
        .digest(raw.toByteArray(Charsets.UTF_8))
    return digest.take(6).joinToString("") { byte -> "%02x".format(byte) }
}
