package com.example.comicdav.network

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
}
