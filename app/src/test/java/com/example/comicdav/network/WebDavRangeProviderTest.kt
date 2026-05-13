package com.example.comicdav.network

import java.io.File
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class WebDavRangeProviderTest {
    @Test
    fun readAheadServesCoveredNextRangeFromMergedWindow() {
        val bytes = ByteArray(128) { it.toByte() }
        val client = RecordingWebDavClient(bytes)
        val provider = WebDavRangeProvider(
            client = client,
            path = "/books/book.cbz",
            size = bytes.size.toLong(),
            readAheadBytes = 32,
        )

        assertArrayEquals(bytes.sliceArray(10..19), provider.readRange(fileId = 1, start = 10, endInclusive = 19))
        assertArrayEquals(bytes.sliceArray(30..40), provider.readRange(fileId = 1, start = 30, endInclusive = 40))

        assertEquals(listOf(10L to 51L), client.rangeCalls)
    }

    private class RecordingWebDavClient(
        private val bytes: ByteArray,
    ) : WebDavClient {
        val rangeCalls = mutableListOf<Pair<Long, Long>>()

        override suspend fun list(path: String): List<WebDavItem> = emptyList()

        override suspend fun head(path: String): RemoteFileInfo =
            RemoteFileInfo(path, bytes.size.toLong(), etag = null, lastModified = null, supportsRange = true)

        override suspend fun readRange(path: String, start: Long, endInclusive: Long): ByteArray {
            rangeCalls += start to endInclusive
            return bytes.sliceArray(start.toInt()..endInclusive.toInt())
        }

        override suspend fun download(path: String, target: File, onBytesRead: (Long) -> Unit): Long {
            error("unused")
        }
    }
}
