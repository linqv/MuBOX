package com.example.comicdav.network

import com.example.comicdav.core.remote.WebDavException
import java.net.URI
import java.net.URLDecoder
import java.util.Locale
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * Resolves WebDAV hrefs without allowing request paths to change the configured endpoint.
 *
 * Redirects are still handled by OkHttp, but every URL created from caller-controlled input is
 * checked here before credentials are attached.
 */
internal class WebDavUrlResolver(
    baseUrl: String,
    private val allowPlaintextHttp: Boolean,
    private val sanitizedUrl: (HttpUrl) -> String,
) {
    private val parsedBaseUrl: HttpUrl = baseUrl.toHttpUrlOrNull()
        ?: throw WebDavException.Network("Invalid WebDAV base URL")
    private val baseHttpUrl: HttpUrl = parsedBaseUrl.withoutUserInfo().withTrailingSlash()
    private val base: String = baseHttpUrl.toString()
    private val baseUri: URI = parseBaseUri(base)
    private val baseOrigin = Origin.from(baseHttpUrl)
    private val mountedPrefixes: List<String> = run {
        val basePath = baseUri.rawPath.orEmpty()
        val mountedPrefix = if (basePath.endsWith("/")) basePath else "$basePath/"
        val decodedMountedPrefix = decodePath(mountedPrefix)
        val encodedMountedPrefix = encodePath(decodedMountedPrefix)
        listOf(mountedPrefix, decodedMountedPrefix, encodedMountedPrefix)
    }

    init {
        requireAllowedTransport(baseHttpUrl)
    }

    fun resolve(path: String): HttpUrl {
        val trimmedPath = path.trim()
        val resolvedWithPossibleUserInfo = when {
            trimmedPath.startsWith("http://", ignoreCase = true) ||
                trimmedPath.startsWith("https://", ignoreCase = true) -> {
                trimmedPath.toHttpUrlOrNull()
                    ?: throw WebDavException.Network("Invalid WebDAV request URL")
            }
            trimmedPath.startsWith("//") || UNSUPPORTED_ABSOLUTE_URL.matches(trimmedPath) -> {
                throw WebDavException.Network("Invalid WebDAV request URL")
            }
            else -> {
                val requestPath = normalizeRequestPath(path)
                baseUri.resolve(requestPath).toString().toHttpUrlOrNull()
                    ?: throw WebDavException.Network("Invalid WebDAV request URL")
            }
        }
        val resolved = resolvedWithPossibleUserInfo.withoutUserInfo()
        requireSameOrigin(resolved)
        requireAllowedTransport(resolved)
        return resolved
    }

    fun requireAllowedTransport(url: HttpUrl) {
        if (url.scheme == "http" && !allowPlaintextHttp) {
            throw WebDavException.Network("Plaintext HTTP is not allowed: ${sanitizedUrl(url)}")
        }
    }

    fun baseOrigin(): String {
        val host = if (baseOrigin.host.contains(":")) {
            "[${baseOrigin.host}]"
        } else {
            baseOrigin.host
        }
        return "${baseOrigin.scheme}://$host:${baseOrigin.port}"
    }

    private fun requireSameOrigin(url: HttpUrl) {
        if (Origin.from(url) != baseOrigin) {
            throw WebDavException.Network(
                "Cross-origin WebDAV request is not allowed: ${sanitizedUrl(url)}",
            )
        }
    }

    private fun normalizeRequestPath(path: String): String {
        if (mountedPrefixes.any { path.startsWith(it) }) {
            return path
        }
        if (mountedPrefixes.any { path.startsWith(it.trimStart('/')) }) {
            return "/$path"
        }
        return path.trimStart('/')
    }

    private fun decodePath(path: String): String =
        try {
            URLDecoder.decode(path.replace("+", "%2B"), Charsets.UTF_8.name())
        } catch (_: IllegalArgumentException) {
            throw WebDavException.Network("Invalid WebDAV request URL")
        }

    private fun encodePath(path: String): String =
        try {
            URI(null, null, path, null).toASCIIString()
        } catch (_: IllegalArgumentException) {
            throw WebDavException.Network("Invalid WebDAV request URL")
        }

    private fun HttpUrl.withoutUserInfo(): HttpUrl =
        newBuilder()
            .username("")
            .password("")
            .build()

    private fun HttpUrl.withTrailingSlash(): HttpUrl =
        if (encodedPath.endsWith("/")) {
            this
        } else {
            newBuilder().encodedPath("$encodedPath/").build()
        }

    private data class Origin(
        val scheme: String,
        val host: String,
        val port: Int,
    ) {
        companion object {
            fun from(url: HttpUrl): Origin =
                Origin(
                    scheme = url.scheme.lowercase(Locale.ROOT),
                    host = url.host.lowercase(Locale.ROOT),
                    port = url.port,
                )
        }
    }

    private companion object {
        val UNSUPPORTED_ABSOLUTE_URL = Regex("""^[a-zA-Z][a-zA-Z0-9+.-]*://.*""")

        fun parseBaseUri(base: String): URI =
            try {
                URI(base)
            } catch (_: Exception) {
                throw WebDavException.Network("Invalid WebDAV base URL")
            }
    }
}
