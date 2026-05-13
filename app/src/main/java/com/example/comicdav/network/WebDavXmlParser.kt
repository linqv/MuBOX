package com.example.comicdav.network

import org.w3c.dom.Element
import java.io.InputStream
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory

object WebDavXmlParser {
    fun parse(input: InputStream, basePath: String): List<WebDavItem> {
        val document = documentBuilderFactory().newDocumentBuilder().parse(input)
        val base = normalizeDirectoryPath(basePath)
        val responses = document.getElementsByTagNameNS("*", "response")

        return buildList {
            for (index in 0 until responses.length) {
                val response = responses.item(index) as? Element ?: continue
                response.toItem(base)?.let(::add)
            }
        }
    }

    private fun documentBuilderFactory(): DocumentBuilderFactory =
        DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
            setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true)
            setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
            setFeature("http://xml.org/sax/features/external-general-entities", false)
            setFeature("http://xml.org/sax/features/external-parameter-entities", false)
        }

    private fun Element.toItem(basePath: String): WebDavItem? {
        val path = childText("href") ?: return null
        val isDirectory = hasDescendant("collection")
        val comparablePath = if (isDirectory) normalizeDirectoryPath(path) else path
        if (comparablePath == basePath) return null

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

    private fun decodedName(path: String): String {
        val trimmed = path.trimEnd('/')
        val encoded = trimmed.substringAfterLast('/')
        return URLDecoder.decode(encoded, StandardCharsets.UTF_8.name())
    }
}
