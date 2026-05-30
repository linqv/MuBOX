package com.example.comicdav.network

import org.w3c.dom.Element
import java.io.InputStream
import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.parsers.ParserConfigurationException

object WebDavXmlParser {
    fun parse(input: InputStream, basePath: String): List<WebDavItem> {
        val document = documentBuilderFactory().newDocumentBuilder().parse(input)
        val base = normalizeDirectoryPath(normalizeHref(basePath, basePath = "/"))
        val responses = document.getElementsByTagNameNS("*", "response")

        return buildList {
            for (index in 0 until responses.length) {
                val response = responses.item(index) as? Element ?: continue
                response.toItem(base)?.let(::add)
            }
        }
    }

    fun parseMetadata(input: InputStream, requestPath: String, supportsRange: Boolean): RemoteFileInfo? {
        val document = documentBuilderFactory().newDocumentBuilder().parse(input)
        val responses = document.getElementsByTagNameNS("*", "response")
        val normalizedRequestPath = normalizeFilePath(requestPath)

        for (index in 0 until responses.length) {
            val response = responses.item(index) as? Element ?: continue
            val href = response.childText("href") ?: continue
            val path = normalizeFilePath(normalizeHref(href, basePath = parentDirectoryPath(requestPath)))
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

    private fun documentBuilderFactory(): DocumentBuilderFactory =
        configureSecurely(DocumentBuilderFactory.newInstance())

    internal fun configureSecurely(factory: DocumentBuilderFactory): DocumentBuilderFactory =
        factory.apply {
            isNamespaceAware = true
            setFeatureIfSupported(XMLConstants.FEATURE_SECURE_PROCESSING, true)
            setFeatureIfSupported("http://apache.org/xml/features/disallow-doctype-decl", true)
            setFeatureIfSupported("http://xml.org/sax/features/external-general-entities", false)
            setFeatureIfSupported("http://xml.org/sax/features/external-parameter-entities", false)
        }

    private fun DocumentBuilderFactory.setFeatureIfSupported(feature: String, value: Boolean) {
        try {
            setFeature(feature, value)
        } catch (_: ParserConfigurationException) {
            // Android parser implementations vary; unsupported hardening features should not block PROPFIND parsing.
        }
    }

    private fun Element.toItem(basePath: String): WebDavItem? {
        val href = childText("href") ?: return null
        val props = successfulPropElements()
        val isDirectory = props.any { it.hasDescendant("collection") }
        val path = normalizeItemPath(normalizeHref(href, basePath), isDirectory)
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

    private fun normalizeHref(href: String, basePath: String): String {
        val path = href.trim()
        val absolutePath = absoluteUriPath(path)
        if (absolutePath != null) return absolutePath
        if (path.startsWith("/")) return path
        if (matchesBaseRoot(path, basePath)) return "/$path"

        return normalizeDirectoryPath(basePath) + path.trimStart('/')
    }

    private fun matchesBaseRoot(path: String, basePath: String): Boolean {
        val baseRoot = normalizeDirectoryPath(basePath).trimStart('/').substringBefore('/') + "/"
        return path.startsWith(baseRoot) || decodedPath(path).startsWith(decodedPath(baseRoot))
    }

    private fun absoluteUriPath(href: String): String? {
        return try {
            val uri = URI(href)
            if (uri.scheme == null) {
                null
            } else {
                val path = uri.rawPath ?: uri.path ?: return null
                if (uri.rawQuery == null) path else "$path?${uri.rawQuery}"
            }
        } catch (_: IllegalArgumentException) {
            null
        }
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
}
