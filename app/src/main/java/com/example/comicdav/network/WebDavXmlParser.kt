package com.example.comicdav.network

import org.w3c.dom.Element
import java.io.InputStream
import java.io.StringReader
import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilder
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.parsers.ParserConfigurationException
import org.xml.sax.InputSource

object WebDavXmlParser {
    fun parse(input: InputStream, basePath: String): List<WebDavItem> {
        val document = secureDocumentBuilder().parse(input)
        val base = normalizeDirectoryPath(normalizeHref(basePath, basePath = "/", baseOrigin = null) ?: "/")
        val responses = document.getElementsByTagNameNS("*", "response")

        return buildList {
            for (index in 0 until responses.length) {
                val response = responses.item(index) as? Element ?: continue
                response.toItem(base, baseOrigin = null)?.let(::add)
            }
        }
    }

    fun parse(input: InputStream, basePath: String, baseOrigin: String?): List<WebDavItem> {
        val document = secureDocumentBuilder().parse(input)
        val base = normalizeDirectoryPath(normalizeHref(basePath, basePath = "/", baseOrigin = baseOrigin) ?: "/")
        val responses = document.getElementsByTagNameNS("*", "response")

        return buildList {
            for (index in 0 until responses.length) {
                val response = responses.item(index) as? Element ?: continue
                response.toItem(base, baseOrigin = baseOrigin)?.let(::add)
            }
        }
    }

    fun parseMetadata(input: InputStream, requestPath: String, supportsRange: Boolean): RemoteFileInfo? {
        val document = secureDocumentBuilder().parse(input)
        val responses = document.getElementsByTagNameNS("*", "response")
        val normalizedRequestPath = normalizeFilePath(requestPath)

        for (index in 0 until responses.length) {
            val response = responses.item(index) as? Element ?: continue
            val href = response.childText("href") ?: continue
            val normalizedHref = normalizeHref(href, basePath = parentDirectoryPath(requestPath), baseOrigin = null) ?: continue
            val path = normalizeFilePath(normalizedHref)
            if (!isSameFilePath(path, normalizedRequestPath)) continue

            val props = response.successfulPropElements()
            val size = props.firstChildText("getcontentlength")?.toLongOrNull() ?: return null
            return RemoteFileInfo(
                path = normalizedRequestPath,
                size = size,
                etag = props.firstChildText("getetag")?.ifEmpty { null },
                lastModified = parseHttpDateMillis(props.firstChildText("getlastmodified")),
                supportsRange = supportsRange,
            )
        }
        return null
    }

    private fun secureDocumentBuilder(): DocumentBuilder =
        documentBuilderFactory().newDocumentBuilder().apply {
            setEntityResolver { _, _ -> InputSource(StringReader("")) }
        }

    private fun documentBuilderFactory(): DocumentBuilderFactory =
        configureSecurely(DocumentBuilderFactory.newInstance())

    internal fun configureSecurely(factory: DocumentBuilderFactory): DocumentBuilderFactory =
        factory.apply {
            isNamespaceAware = true
            isXIncludeAware = false
            isExpandEntityReferences = false
            setRequiredFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true)
            setRequiredFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
            setRequiredFeature("http://xml.org/sax/features/external-general-entities", false)
            setRequiredFeature("http://xml.org/sax/features/external-parameter-entities", false)
            setRequiredFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false)
            setAttributeIfSupported(ACCESS_EXTERNAL_DTD, "")
            setAttributeIfSupported(ACCESS_EXTERNAL_SCHEMA, "")
        }

    private fun DocumentBuilderFactory.setRequiredFeature(feature: String, value: Boolean) {
        try {
            setFeature(feature, value)
        } catch (error: ParserConfigurationException) {
            throw ParserConfigurationException("Required XML secure feature is not supported: $feature")
                .also { it.initCause(error) }
        }
    }

    private fun DocumentBuilderFactory.setAttributeIfSupported(name: String, value: String) {
        try {
            setAttribute(name, value)
        } catch (_: IllegalArgumentException) {
            // The required feature gates above block XXE. These JAXP attributes are extra hardening where present.
        }
    }

    private fun Element.toItem(basePath: String, baseOrigin: String?): WebDavItem? {
        val href = childText("href") ?: return null
        val props = successfulPropElements()
        val isDirectory = props.any { it.hasDescendant("collection") }
        val normalizedHref = normalizeHref(href, basePath, baseOrigin) ?: return null
        val path = normalizeItemPath(normalizedHref, isDirectory)
        val comparablePath = if (isDirectory) normalizeDirectoryPath(path) else path
        if (isSamePath(comparablePath, basePath)) return null

        return WebDavItem(
            name = decodedName(path),
            path = path,
            isDirectory = isDirectory,
            size = props.firstChildText("getcontentlength")?.toLongOrNull(),
            etag = props.firstChildText("getetag")?.ifEmpty { null },
            lastModified = parseHttpDateMillis(props.firstChildText("getlastmodified")),
        )
    }

    private fun Element.childText(localName: String): String? {
        val nodes = getElementsByTagNameNS("*", localName)
        if (nodes.length == 0) return null
        return nodes.item(0).textContent.trim()
    }

    private fun Element.hasDescendant(localName: String): Boolean =
        getElementsByTagNameNS("*", localName).length > 0

    private fun Element.successfulPropElements(): List<Element> {
        val propstats = getElementsByTagNameNS("*", "propstat")
        if (propstats.length == 0) {
            return listOfNotNull(firstDirectOrDescendant("prop"))
        }
        return buildList {
            for (index in 0 until propstats.length) {
                val propstat = propstats.item(index) as? Element ?: continue
                if (!propstat.isSuccessfulPropstat()) continue
                propstat.firstDirectOrDescendant("prop")?.let(::add)
            }
        }
    }

    private fun Element.isSuccessfulPropstat(): Boolean {
        val status = childText("status") ?: return true
        return STATUS_CODE.find(status)?.groupValues?.getOrNull(1)?.toIntOrNull() in 200..299
    }

    private fun Element.firstDirectOrDescendant(localName: String): Element? {
        val nodes = getElementsByTagNameNS("*", localName)
        return if (nodes.length == 0) null else nodes.item(0) as? Element
    }

    private fun List<Element>.firstChildText(localName: String): String? =
        firstNotNullOfOrNull { prop -> prop.childText(localName) }

    private fun normalizeDirectoryPath(path: String): String = if (path.endsWith("/")) path else "$path/"

    private fun normalizeItemPath(path: String, isDirectory: Boolean): String =
        if (isDirectory) normalizeDirectoryPath(path) else path

    private fun normalizeFilePath(path: String): String = path.trimEnd('/').ifBlank { "/" }

    private fun normalizeHref(href: String, basePath: String, baseOrigin: String?): String? {
        val path = href.trim()
        val absoluteResult = absoluteUriPath(path, baseOrigin)
        when (absoluteResult) {
            is AbsoluteHrefResult.Accepted -> return absoluteResult.path
            is AbsoluteHrefResult.Rejected -> return null
            is AbsoluteHrefResult.NotAbsolute -> { }
        }
        if (path.startsWith("/")) return path
        if (matchesBaseRoot(path, basePath)) return "/$path"

        return normalizeDirectoryPath(basePath) + path.trimStart('/')
    }

    private fun matchesBaseRoot(path: String, basePath: String): Boolean {
        val baseRoot = normalizeDirectoryPath(basePath).trimStart('/').substringBefore('/') + "/"
        return path.startsWith(baseRoot) || decodedPath(path).startsWith(decodedPath(baseRoot))
    }

    private sealed class AbsoluteHrefResult {
        data class Accepted(val path: String) : AbsoluteHrefResult()
        object Rejected : AbsoluteHrefResult()
        object NotAbsolute : AbsoluteHrefResult()
    }

    private fun absoluteUriPath(href: String, baseOrigin: String?): AbsoluteHrefResult {
        return try {
            val uri = URI(href)
            if (uri.scheme == null) {
                AbsoluteHrefResult.NotAbsolute
            } else {
                if (baseOrigin != null) {
                    val hrefOrigin = originFromUri(uri) ?: return AbsoluteHrefResult.Rejected
                    if (!hrefOrigin.equals(baseOrigin, ignoreCase = true)) return AbsoluteHrefResult.Rejected
                }
                val path = uri.rawPath ?: uri.path ?: return AbsoluteHrefResult.Rejected
                val result = if (uri.rawQuery == null) path else "$path?${uri.rawQuery}"
                AbsoluteHrefResult.Accepted(result)
            }
        } catch (_: IllegalArgumentException) {
            AbsoluteHrefResult.NotAbsolute
        }
    }

    private fun originFromUri(uri: URI): String? {
        val scheme = uri.scheme ?: return null
        val host = uri.host ?: return null
        val port = when {
            uri.port > 0 -> uri.port
            scheme.equals("http", ignoreCase = true) -> 80
            scheme.equals("https", ignoreCase = true) -> 443
            else -> return null
        }
        return "$scheme://$host:$port"
    }

    private fun isSamePath(left: String, right: String): Boolean =
        decodedPath(normalizeDirectoryPath(left)) == decodedPath(normalizeDirectoryPath(right))

    private fun isSameFilePath(left: String, right: String): Boolean =
        decodedPath(normalizeFilePath(left)) == decodedPath(normalizeFilePath(right))

    private fun decodedPath(path: String): String =
        URLDecoder.decode(path.replace("+", "%2B"), StandardCharsets.UTF_8.name())

    private fun decodedName(path: String): String {
        val trimmed = path.trimEnd('/')
        val encoded = trimmed.substringAfterLast('/')
        return URLDecoder.decode(encoded.replace("+", "%2B"), StandardCharsets.UTF_8.name())
    }

    private fun parentDirectoryPath(path: String): String {
        val normalized = path.takeIf { it.isNotBlank() } ?: "/"
        val withoutTrailingSlash = normalized.trimEnd('/')
        val lastSlashIndex = withoutTrailingSlash.lastIndexOf('/')
        return if (lastSlashIndex <= 0) "/" else withoutTrailingSlash.substring(0, lastSlashIndex + 1)
    }

    private fun parseHttpDateMillis(value: String?): Long? =
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

    private val STATUS_CODE = Regex("""\b(\d{3})\b""")
    private const val ACCESS_EXTERNAL_DTD = "http://javax.xml.XMLConstants/property/accessExternalDTD"
    private const val ACCESS_EXTERNAL_SCHEMA = "http://javax.xml.XMLConstants/property/accessExternalSchema"
}
