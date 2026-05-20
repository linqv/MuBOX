package com.example.comicdav.network

import kotlinx.coroutines.test.runTest
import okhttp3.MediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody
import okhttp3.MediaType.Companion.toMediaType
import okio.Buffer
import okio.BufferedSource
import okio.Source
import okio.Timeout
import okio.buffer
import org.junit.Assert.assertTrue
import org.junit.Test

class OkHttpWebDavClientTest {
    @Test
    fun invalidContentRangeClosesResponseBody() = runTest {
        val body = CloseTrackingBody("abc")
        val httpClient = OkHttpClient.Builder()
            .addInterceptor { chain ->
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(206)
                    .message("Partial Content")
                    .header("Content-Range", "bytes 1-3/10")
                    .body(body)
                    .build()
            }
            .build()
        val client = OkHttpWebDavClient(
            baseUrl = "http://example.test/dav/",
            username = null,
            password = null,
            httpClient = httpClient,
        )

        val result = runCatching {
            client.openRangeStream("/movie.mp4", start = 0L, endInclusive = 3L)
        }

        assertTrue(result.exceptionOrNull() is WebDavException.InvalidContentRange)
        assertTrue(body.closed)
    }

    private class CloseTrackingBody(
        text: String,
    ) : ResponseBody() {
        var closed: Boolean = false
            private set

        private val bytes = text.toByteArray()
        private val source = object : Source {
            private val buffer = Buffer().write(bytes)

            override fun read(sink: Buffer, byteCount: Long): Long =
                buffer.read(sink, byteCount)

            override fun timeout(): Timeout = Timeout.NONE

            override fun close() {
                closed = true
                buffer.clear()
            }
        }.buffer()

        override fun contentType(): MediaType = "video/mp4".toMediaType()

        override fun contentLength(): Long = bytes.size.toLong()

        override fun source(): BufferedSource = source
    }
}
