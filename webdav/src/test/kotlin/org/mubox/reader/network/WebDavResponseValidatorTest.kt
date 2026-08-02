package org.mubox.reader.network

import org.mubox.reader.core.remote.WebDavException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WebDavResponseValidatorTest {
    @Test
    fun validatesPartialContentMetadataWithoutOkHttpResponse() {
        val validated = WebDavResponseValidator.validateRange(
            statusCode = 206,
            contentRangeHeader = "bytes 2-4/10",
            declaredContentLength = 3,
            expectedStart = 2,
            expectedEndInclusive = 4,
            sanitizedUrl = "https://example.test/file",
        )

        assertEquals(2L, validated.contentRange.start)
        assertEquals(4L, validated.contentRange.endInclusive)
        assertEquals(10L, validated.totalSize)
        assertEquals(3L, validated.contentLength)
    }

    @Test
    fun mapsOverflowingContentRangeToDomainError() {
        val error = runCatching {
            WebDavResponseValidator.validateRange(
                statusCode = 206,
                contentRangeHeader = "bytes 0-999999999999999999999999/10",
                declaredContentLength = -1,
                expectedStart = 0,
                expectedEndInclusive = null,
                sanitizedUrl = "https://example.test/file",
            )
        }.exceptionOrNull()

        assertTrue(error is WebDavException.InvalidContentRange)
    }

    @Test
    fun rejectsBodyLengthThatDisagreesWithContentRange() {
        val error = runCatching {
            WebDavResponseValidator.validateRange(
                statusCode = 206,
                contentRangeHeader = "bytes 2-4/10",
                declaredContentLength = 2,
                expectedStart = 2,
                expectedEndInclusive = 4,
                sanitizedUrl = "https://example.test/file",
            )
        }.exceptionOrNull()

        assertTrue(error is WebDavException.InvalidContentRange)
    }

    @Test
    fun httpStatusFailureContainsOnlyProvidedSanitizedUrl() {
        val error = runCatching {
            WebDavResponseValidator.requireSuccessful(
                statusCode = 401,
                operation = "GET",
                sanitizedUrl = "https://example.test/private?token=<redacted>",
            )
        }.exceptionOrNull()

        assertTrue(error is WebDavException.HttpStatus)
        assertTrue(error?.message.orEmpty().contains("token=<redacted>"))
    }
}
