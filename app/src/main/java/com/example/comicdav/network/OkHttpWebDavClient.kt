package com.example.comicdav.network

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Credentials
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.URI
import java.util.Locale

class OkHttpWebDavClient(
    private val baseUrl: String,
    private val username: String?,
    private val password: String?,
    private val httpClient: OkHttpClient = OkHttpClient(),
) : WebDavClient {
    override suspend fun list(path: String): List<WebDavItem> = withContext(Dispatchers.IO) {
        val body = """<?xml version="1.0" encoding="utf-8" ?>
            <d:propfind xmlns:d="DAV:">
                <d:prop>
                    <d:resourcetype />
                    <d:getcontentlength />
                    <d:getetag />
                    <d:getlastmodified />
                </d:prop>
            </d:propfind>
        """.trimIndent().toRequestBody("application/xml; charset=utf-8".toMediaType())
        val request = requestBuilder(path)
            .method("PROPFIND", body)
            .header("Depth", "1")
            .build()
        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw WebDavException.HttpStatus(response.code, "PROPFIND failed with HTTP ${response.code}")
            }
            val responseBody = response.body ?: throw WebDavException.MissingMetadata("PROPFIND response body is empty")
            WebDavXmlParser.parse(responseBody.byteStream(), path)
        }
    }

    override suspend fun head(path: String): RemoteFileInfo = withContext(Dispatchers.IO) {
        val request = requestBuilder(path).head().build()
        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw WebDavException.HttpStatus(response.code, "HEAD failed with HTTP ${response.code}")
            }
            val size = response.header("Content-Length")?.toLongOrNull()
                ?: throw WebDavException.MissingMetadata("HEAD response is missing Content-Length")
            RemoteFileInfo(
                path = path,
                size = size,
                etag = response.header("ETag"),
                lastModified = response.header("Last-Modified")?.hashCode()?.toLong(),
                supportsRange = response.header("Accept-Ranges")?.equals("bytes", ignoreCase = true) == true,
            )
        }
    }

    override suspend fun readRange(path: String, start: Long, endInclusive: Long): ByteArray =
        withContext(Dispatchers.IO) {
            val request = requestBuilder(path)
                .get()
                .header("Range", "bytes=$start-$endInclusive")
                .build()
            httpClient.newCall(request).execute().use { response ->
                when (response.code) {
                    206 -> {
                        validateContentRange(
                            header = response.header("Content-Range"),
                            expectedStart = start,
                            expectedEndInclusive = endInclusive,
                        )
                        response.body?.bytes()
                            ?: throw WebDavException.MissingMetadata("Range response body is empty")
                    }
                    200 -> throw WebDavException.RangeNotSupported()
                    else -> throw WebDavException.HttpStatus(response.code, "Range GET failed with HTTP ${response.code}")
                }
            }
        }

    private fun requestBuilder(path: String): Request.Builder =
        Request.Builder()
            .url(resolveUrl(path))
            .also { builder ->
                if (!username.isNullOrBlank() && password != null) {
                    builder.header("Authorization", Credentials.basic(username, password))
                }
            }

    private fun resolveUrl(path: String): String {
        val base = if (baseUrl.endsWith("/")) baseUrl else "$baseUrl/"
        val relative = path.trimStart('/')
        return URI(base).resolve(relative).toString()
    }

    private fun validateContentRange(
        header: String?,
        expectedStart: Long,
        expectedEndInclusive: Long,
    ) {
        val value = header ?: throw WebDavException.InvalidContentRange("Missing Content-Range header")
        val match = CONTENT_RANGE.matchEntire(value.lowercase(Locale.US))
            ?: throw WebDavException.InvalidContentRange("Invalid Content-Range header: $value")
        val start = match.groupValues[1].toLong()
        val end = match.groupValues[2].toLong()
        if (start != expectedStart || end != expectedEndInclusive) {
            throw WebDavException.InvalidContentRange(
                "Expected Content-Range bytes $expectedStart-$expectedEndInclusive but got $value",
            )
        }
    }

    companion object {
        private val CONTENT_RANGE = Regex("""bytes\s+(\d+)-(\d+)/(\d+|\*)""")
    }
}
