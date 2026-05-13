package com.example.comicdav.network

import org.w3c.dom.Element
import java.io.InputStream
import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
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
        val isDirectory = hasDescendant("collection")
        val path = normalizeItemPath(normalizeHref(href, basePath), isDirectory)
        val comparablePath = if (isDirectory) normalizeDirectoryPath(path) else path
        if (isSamePath(comparablePath, basePath)) return null

        return WebDavItem(
            name = decodedName(path),
            path = path,
            isDirectory = isDirectory,
            size = childText("getcontentlength")?.toLongOrNull(),
            etag = childText("getetag")?.ifEmpty { null },
            lastModified = null,
        )
    }

    private fun Element.childText(localName: String): String? {
        val nodes = getElementsByTagNameNS("*", localName)
        if (nodes.length == 0) return null
        return nodes.item(0).textContent.trim()
    }

    private fun Element.hasDescendant(localName: String): Boolean =
        getElementsByTagNameNS("*", localName).length > 0

    private fun normalizeDirectoryPath(path: String): String = if (path.endsWith("/")) path else "$path/"

    private fun normalizeItemPath(path: String, isDirectory: Boolean): String =
        if (isDirectory) normalizeDirectoryPath(path) else path

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

    private fun decodedPath(path: String): String =
        URLDecoder.decode(path, StandardCharsets.UTF_8.name())

    private fun decodedName(path: String): String {
        val trimmed = path.trimEnd('/')
        val encoded = trimmed.substringAfterLast('/')
        return URLDecoder.decode(encoded, StandardCharsets.UTF_8.name())
    }
}
