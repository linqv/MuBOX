package com.example.comicdav.network

import javax.xml.parsers.DocumentBuilder
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.parsers.ParserConfigurationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WebDavXmlParserTest {
    @Test
    fun parsesDirectoryAndComicEntries() {
        val xml = """<?xml version="1.0"?><d:multistatus xmlns:d="DAV:">
            <d:response><d:href>/comics/</d:href><d:propstat><d:prop><d:resourcetype><d:collection/></d:resourcetype></d:prop></d:propstat></d:response>
            <d:response><d:href>/comics/01.cbz</d:href><d:propstat><d:prop><d:getcontentlength>123</d:getcontentlength><d:getetag>"abc"</d:getetag></d:prop></d:propstat></d:response>
        </d:multistatus>"""

        val items = WebDavXmlParser.parse(xml.byteInputStream(), basePath = "/comics/")

        assertEquals(listOf("01.cbz"), items.map { it.name })
        assertEquals(123L, items.single().size)
        assertEquals("\"abc\"", items.single().etag)
        assertFalse(items.single().isDirectory)
    }

    @Test
    fun parsesDirectoryItemsAndDecodesNames() {
        val xml = """<?xml version="1.0"?><d:multistatus xmlns:d="DAV:">
            <d:response><d:href>/comics/%E6%BC%AB%E7%94%BB%20A/</d:href><d:propstat><d:prop><d:resourcetype><d:collection/></d:resourcetype></d:prop></d:propstat></d:response>
        </d:multistatus>"""

        val items = WebDavXmlParser.parse(xml.byteInputStream(), basePath = "/comics/")

        assertEquals("漫画 A", items.single().name)
        assertEquals("/comics/%E6%BC%AB%E7%94%BB%20A/", items.single().path)
        assertTrue(items.single().isDirectory)
        assertNull(items.single().size)
    }

    @Test
    fun ignoresCurrentDirectoryResponse() {
        val xml = """<?xml version="1.0"?><d:multistatus xmlns:d="DAV:">
            <d:response><d:href>/comics/</d:href><d:propstat><d:prop><d:resourcetype><d:collection/></d:resourcetype></d:prop></d:propstat></d:response>
        </d:multistatus>"""

        val items = WebDavXmlParser.parse(xml.byteInputStream(), basePath = "/comics/")

        assertTrue(items.isEmpty())
    }

    @Test
    fun normalizesDirectoryHrefWithoutTrailingSlash() {
        val xml = """<?xml version="1.0"?><d:multistatus xmlns:d="DAV:">
            <d:response><d:href>/comics/Series</d:href><d:propstat><d:prop><d:resourcetype><d:collection/></d:resourcetype></d:prop></d:propstat></d:response>
        </d:multistatus>"""

        val items = WebDavXmlParser.parse(xml.byteInputStream(), basePath = "/comics/")

        assertEquals("/comics/Series/", items.single().path)
        assertTrue(items.single().isDirectory)
    }

    @Test
    fun resolvesRelativeHrefAgainstCurrentDirectory() {
        val xml = """<?xml version="1.0"?><d:multistatus xmlns:d="DAV:">
            <d:response><d:href>第01卷</d:href><d:propstat><d:prop><d:resourcetype><d:collection/></d:resourcetype></d:prop></d:propstat></d:response>
        </d:multistatus>"""

        val items = WebDavXmlParser.parse(xml.byteInputStream(), basePath = "/webdav/%E6%BC%AB%E7%94%BB/")

        assertEquals("/webdav/%E6%BC%AB%E7%94%BB/第01卷/", items.single().path)
        assertEquals("第01卷", items.single().name)
        assertTrue(items.single().isDirectory)
    }

    @Test
    fun treatsHrefMatchingCurrentRootWithoutLeadingSlashAsRootRelative() {
        val xml = """<?xml version="1.0"?><d:multistatus xmlns:d="DAV:">
            <d:response><d:href>webdav/%E6%BC%AB%E7%94%BB/%E9%BB%91%E8%B0%B7%E9%9B%A8%E6%B3%BD/</d:href><d:propstat><d:prop><d:resourcetype><d:collection/></d:resourcetype></d:prop></d:propstat></d:response>
        </d:multistatus>"""

        val items = WebDavXmlParser.parse(xml.byteInputStream(), basePath = "/webdav/%E6%BC%AB%E7%94%BB/")

        assertEquals("/webdav/%E6%BC%AB%E7%94%BB/%E9%BB%91%E8%B0%B7%E9%9B%A8%E6%B3%BD/", items.single().path)
        assertEquals("黑谷雨泽", items.single().name)
        assertTrue(items.single().isDirectory)
    }

    @Test
    fun extractsRequestPathFromAbsoluteHref() {
        val xml = """<?xml version="1.0"?><d:multistatus xmlns:d="DAV:">
            <d:response><d:href>https://example.test/webdav/%E6%BC%AB%E7%94%BB/Book.cbz</d:href><d:propstat><d:prop><d:getcontentlength>456</d:getcontentlength></d:prop></d:propstat></d:response>
        </d:multistatus>"""

        val items = WebDavXmlParser.parse(xml.byteInputStream(), basePath = "/webdav/%E6%BC%AB%E7%94%BB/")

        assertEquals("/webdav/%E6%BC%AB%E7%94%BB/Book.cbz", items.single().path)
        assertEquals("Book.cbz", items.single().name)
        assertFalse(items.single().isDirectory)
    }

    @Test
    fun secureFeatureConfigurationIgnoresUnsupportedParserFeatures() {
        val factory = RejectingFeatureFactory()

        WebDavXmlParser.configureSecurely(factory)

        assertTrue(factory.isNamespaceAware)
        assertTrue(factory.rejectedFeatures.contains("http://javax.xml.XMLConstants/feature/secure-processing"))
    }

    private class RejectingFeatureFactory : DocumentBuilderFactory() {
        val rejectedFeatures = mutableListOf<String>()

        override fun newDocumentBuilder(): DocumentBuilder = error("unused")

        override fun setAttribute(name: String?, value: Any?) = Unit

        override fun getAttribute(name: String?): Any = error("unused")

        override fun setFeature(name: String, value: Boolean) {
            rejectedFeatures += name
            throw ParserConfigurationException(name)
        }

        override fun getFeature(name: String?): Boolean = false
    }
}
