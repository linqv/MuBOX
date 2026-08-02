package org.mubox.reader.network

import org.mubox.reader.core.remote.WebDavException
import okhttp3.HttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WebDavUrlResolverTest {
    @Test
    fun resolvesMountedPathsAndStripsCallerControlledUserInfo() {
        val resolver = resolver("https://base-user:base-pass@example.test/webdav/")

        val resolved = resolver.resolve("https://path-user:path-pass@example.test/webdav/books/a.cbz")

        assertEquals("https://example.test/webdav/books/a.cbz", resolved.toString())
        assertEquals("https://example.test:443", resolver.baseOrigin())
    }

    @Test
    fun rejectsCrossOriginSchemeRelativeAndUnsupportedAbsoluteUrls() {
        val resolver = resolver("https://example.test/webdav/")

        val crossOrigin = runCatching { resolver.resolve("https://attacker.test/file") }.exceptionOrNull()
        val schemeRelative = runCatching { resolver.resolve("//attacker.test/file") }.exceptionOrNull()
        val unsupported = runCatching { resolver.resolve("file:///etc/passwd") }.exceptionOrNull()

        assertTrue(crossOrigin is WebDavException.Network)
        assertTrue(schemeRelative is WebDavException.Network)
        assertTrue(unsupported is WebDavException.Network)
    }

    @Test
    fun rejectsPlaintextDowngradeBeforeBuildingRequest() {
        val resolver = resolver("https://example.test/webdav/")

        val error = runCatching {
            resolver.resolve("http://example.test/webdav/file")
        }.exceptionOrNull()

        assertTrue(error is WebDavException.Network)
    }

    @Test
    fun securityFailuresUseSanitizedUrlOnly() {
        val resolver = resolver("https://example.test/webdav/")
        val error = runCatching {
            resolver.resolve("https://user:secret@attacker.test/private?token=open-sesame")
        }.exceptionOrNull()
        val message = error?.message.orEmpty()

        assertFalse(message.contains("secret"))
        assertFalse(message.contains("open-sesame"))
        assertTrue(message.contains("attacker.test"))
    }

    private fun resolver(baseUrl: String): WebDavUrlResolver =
        WebDavUrlResolver(
            baseUrl = baseUrl,
            allowPlaintextHttp = false,
            sanitizedUrl = ::sanitize,
        )

    private fun sanitize(url: HttpUrl): String =
        "${url.scheme}://${url.host}:${url.port}${url.encodedPath}"
}
