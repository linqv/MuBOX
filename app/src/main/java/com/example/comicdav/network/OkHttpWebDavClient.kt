package com.example.comicdav.network

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Credentials
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.net.URI
import java.net.URLDecoder
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
                throw WebDavException.HttpStatus(
                    response.code,
                    "PROPFIND failed with HTTP ${response.code}: ${request.url}",
                )
            }
            val responseBody = response.body ?: throw WebDavException.MissingMetadata("PROPFIND response body is empty")
            WebDavXmlParser.parse(responseBody.byteStream(), path)
        }
    }

    override suspend fun head(path: String): RemoteFileInfo = withContext(Dispatchers.IO) {
        val request = requestBuilder(path).head().build()
        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw WebDavException.HttpStatus(response.code, "HEAD failed with HTTP ${response.code}: ${request.url}")
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
                    else -> throw WebDavException.HttpStatus(
                        response.code,
                        "Range GET failed with HTTP ${response.code}: ${request.url}",
                    )
                }
            }
        }

    override suspend fun download(path: String, target: File, onBytesRead: (Long) -> Unit): Long =
        withContext(Dispatchers.IO) {
            val request = requestBuilder(path).get().build()
            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    throw WebDavException.HttpStatus(response.code, "GET failed with HTTP ${response.code}: ${request.url}")
                }
                val body = response.body ?: throw WebDavException.MissingMetadata("GET response body is empty")
                target.parentFile?.mkdirs()
                var total = 0L
                body.byteStream().use { input ->
                    target.outputStream().use { output ->
                        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                        while (true) {
                            val read = input.read(buffer)
                            if (read == -1) break
                            output.write(buffer, 0, read)
                            total += read
                            onBytesRead(total)
                        }
                    }
                }
                total
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
        if (path.startsWith("http://", ignoreCase = true) || path.startsWith("https://", ignoreCase = true)) {
            return path
        }

        val base = if (baseUrl.endsWith("/")) baseUrl else "$baseUrl/"
        val baseUri = URI(base)
        val basePath = baseUri.rawPath.orEmpty()
        val mountedPrefix = if (basePath.endsWith("/")) basePath else "$basePath/"
        val decodedMountedPrefix = decodePath(mountedPrefix)
        val encodedMountedPrefix = encodePath(decodedMountedPrefix)
        val requestPath = normalizeRequestPath(
            path = path,
            mountedPrefixes = listOf(mountedPrefix, decodedMountedPrefix, encodedMountedPrefix),
        )
        return URI(base).resolve(requestPath).toString()
    }

    private fun normalizeRequestPath(
        path: String,
        mountedPrefixes: List<String>,
    ): String {
        if (mountedPrefixes.any { path.startsWith(it) }) {
            return path
        }
        if (mountedPrefixes.any { path.startsWith(it.trimStart('/')) }) {
            return "/$path"
        }
        return path.trimStart('/')
    }

    private fun decodePath(path: String): String =
        URLDecoder.decode(path, Charsets.UTF_8.name())

    private fun encodePath(path: String): String =
        URI(null, null, path, null).toASCIIString()

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
