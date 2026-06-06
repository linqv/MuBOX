package com.example.comicdav.network

import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilder
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.parsers.ParserConfigurationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.xml.sax.SAXException

class WebDavXmlParserTest {
    @Test
    fun parsesDirectoryAndComicEntries() {
        val xml = """<?xml version="1.0"?><d:multistatus xmlns:d="DAV:">
            <d:response><d:href>/comics/</d:href><d:propstat><d:prop><d:resourcetype><d:collection/></d:resourcetype></d:prop></d:propstat></d:response>
            <d:response><d:href>/comics/01.cbz</d:href><d:propstat><d:prop><d:getcontentlength>123</d:getcontentlength><d:getetag>"abc"</d:getetag><d:getlastmodified>Wed, 21 Oct 2015 07:28:00 GMT</d:getlastmodified></d:prop></d:propstat></d:response>
        </d:multistatus>"""

        val items = WebDavXmlParser.parse(xml.byteInputStream(), basePath = "/comics/")

        assertEquals(listOf("01.cbz"), items.map { it.name })
        assertEquals(123L, items.single().size)
        assertEquals("\"abc\"", items.single().etag)
        assertEquals(1_445_412_480_000L, items.single().lastModified)
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
    fun preservesPlusSignsInHrefPathSegments() {
        val xml = """<?xml version="1.0"?><d:multistatus xmlns:d="DAV:">
            <d:response><d:href>/comics/A+B.cbz</d:href><d:propstat><d:prop><d:getcontentlength>123</d:getcontentlength></d:prop></d:propstat></d:response>
        </d:multistatus>"""

        val items = WebDavXmlParser.parse(xml.byteInputStream(), basePath = "/comics/")

        assertEquals("A+B.cbz", items.single().name)
        assertEquals("/comics/A+B.cbz", items.single().path)
    }

    @Test
    fun ignoresPropertiesFromNonSuccessfulPropstat() {
        val xml = """<?xml version="1.0"?><d:multistatus xmlns:d="DAV:">
            <d:response>
                <d:href>/comics/01.cbz</d:href>
                <d:propstat>
                    <d:prop><d:getcontentlength>999</d:getcontentlength></d:prop>
                    <d:status>HTTP/1.1 404 Not Found</d:status>
                </d:propstat>
                <d:propstat>
                    <d:prop><d:getetag>"ok"</d:getetag></d:prop>
                    <d:status>HTTP/1.1 200 OK</d:status>
                </d:propstat>
            </d:response>
        </d:multistatus>"""

        val item = WebDavXmlParser.parse(xml.byteInputStream(), basePath = "/comics/").single()

        assertNull(item.size)
        assertEquals("\"ok\"", item.etag)
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
    fun acceptsAbsoluteHrefMatchingBaseOrigin() {
        val xml = """<?xml version="1.0"?><d:multistatus xmlns:d="DAV:">
            <d:response><d:href>https://example.test/webdav/Book.cbz</d:href><d:propstat><d:prop><d:getcontentlength>100</d:getcontentlength></d:prop></d:propstat></d:response>
        </d:multistatus>"""

        val items = WebDavXmlParser.parse(
            xml.byteInputStream(),
            basePath = "/webdav/",
            baseOrigin = "https://example.test:443",
        )

        assertEquals("/webdav/Book.cbz", items.single().path)
    }

    @Test
    fun rejectsAbsoluteHrefFromDifferentOrigin() {
        val xml = """<?xml version="1.0"?><d:multistatus xmlns:d="DAV:">
            <d:response><d:href>https://evil.example/webdav/Book.cbz</d:href><d:propstat><d:prop><d:getcontentlength>100</d:getcontentlength></d:prop></d:propstat></d:response>
        </d:multistatus>"""

        val items = WebDavXmlParser.parse(
            xml.byteInputStream(),
            basePath = "/webdav/",
            baseOrigin = "https://example.test:443",
        )

        assertTrue(items.isEmpty())
    }

    @Test
    fun rejectsAbsoluteHrefWithDifferentPort() {
        val xml = """<?xml version="1.0"?><d:multistatus xmlns:d="DAV:">
            <d:response><d:href>https://example.test:8443/webdav/Book.cbz</d:href><d:propstat><d:prop><d:getcontentlength>100</d:getcontentlength></d:prop></d:propstat></d:response>
        </d:multistatus>"""

        val items = WebDavXmlParser.parse(
            xml.byteInputStream(),
            basePath = "/webdav/",
            baseOrigin = "https://example.test:443",
        )

        assertTrue(items.isEmpty())
    }

    @Test
    fun rejectsDoctypeDeclarations() {
        val xml = """<?xml version="1.0"?>
            <!DOCTYPE d:multistatus [
                <!ENTITY xxe SYSTEM "file:///etc/passwd">
            ]>
            <d:multistatus xmlns:d="DAV:">
                <d:response><d:href>/comics/&xxe;</d:href></d:response>
            </d:multistatus>
        """.trimIndent()

        val result = runCatching {
            WebDavXmlParser.parse(xml.byteInputStream(), basePath = "/comics/")
        }

        val error = result.exceptionOrNull()
        assertTrue(error is WebDavException.InvalidResponse)
        assertTrue(error?.cause is SAXException)
    }

    @Test
    fun secureFeatureConfigurationToleratesUnsupportedParserFeatures() {
        val factory = RejectingFeatureFactory()

        val result = runCatching {
            WebDavXmlParser.configureSecurely(factory)
        }

        assertTrue(result.isSuccess)
        assertTrue(factory.isNamespaceAware)
        assertTrue(factory.rejectedFeatures.contains(XMLConstants.FEATURE_SECURE_PROCESSING))
    }

    @Test
    fun secureFeatureConfigurationToleratesUnsupportedFactoryToggles() {
        val factory = RejectingToggleFactory()

        val result = runCatching {
            WebDavXmlParser.configureSecurely(factory)
        }

        assertTrue(result.isSuccess)
        assertTrue(factory.xIncludeAttempted)
        assertTrue(factory.expandEntitiesAttempted)
    }

    @Test
    fun secureFeatureConfigurationToleratesUnsupportedAttributes() {
        val factory = RejectingAttributeFactory()

        val result = runCatching {
            WebDavXmlParser.configureSecurely(factory)
        }

        assertTrue(result.isSuccess)
        assertTrue(factory.rejectedAttributes.contains("http://javax.xml.XMLConstants/property/accessExternalDTD"))
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

    private class RejectingToggleFactory : DocumentBuilderFactory() {
        var xIncludeAttempted = false
            private set
        var expandEntitiesAttempted = false
            private set

        override fun newDocumentBuilder(): DocumentBuilder = error("unused")

        override fun setAttribute(name: String?, value: Any?) = Unit

        override fun getAttribute(name: String?): Any = error("unused")

        override fun setFeature(name: String, value: Boolean) = Unit

        override fun getFeature(name: String?): Boolean = false

        override fun setXIncludeAware(state: Boolean) {
            xIncludeAttempted = true
            throw UnsupportedOperationException("This parser does not support specification \"Unknown\" version \"0.0\"")
        }

        override fun setExpandEntityReferences(expandEntityRef: Boolean) {
            expandEntitiesAttempted = true
            throw UnsupportedOperationException("expand entity references unsupported")
        }
    }

    private class RejectingAttributeFactory : DocumentBuilderFactory() {
        val rejectedAttributes = mutableListOf<String>()

        override fun newDocumentBuilder(): DocumentBuilder = error("unused")

        override fun setAttribute(name: String?, value: Any?) {
            rejectedAttributes += name.orEmpty()
            throw UnsupportedOperationException("attribute unsupported")
        }

        override fun getAttribute(name: String?): Any = error("unused")

        override fun setFeature(name: String, value: Boolean) = Unit

        override fun getFeature(name: String?): Boolean = false
    }
}
