package org.mubox.reader.network

import org.mubox.reader.core.remote.ContentRange
import org.mubox.reader.core.remote.WebDavException
import java.util.Locale

internal data class ValidatedRangeResponse(
    val contentRange: ContentRange,
    val contentLength: Long,
) {
    val totalSize: Long?
        get() = contentRange.totalSize.takeIf { it >= 0L }
}

/**
 * Pure validation for HTTP response metadata. It deliberately receives only a sanitized URL so
 * failures cannot accidentally expose credentials or query parameters.
 */
internal object WebDavResponseValidator {
    private val contentRangePattern = Regex("""bytes\s+(\d+)-(\d+)/(\d+|\*)""")

    fun requireSuccessful(
        statusCode: Int,
        operation: String,
        sanitizedUrl: String,
    ) {
        if (statusCode !in 200..299) {
            throw httpStatus(statusCode, operation, sanitizedUrl)
        }
    }

    fun requireStatus(
        statusCode: Int,
        expectedStatus: Int,
        operation: String,
        sanitizedUrl: String,
    ) {
        if (statusCode != expectedStatus) {
            throw httpStatus(statusCode, operation, sanitizedUrl)
        }
    }

    fun validateRange(
        statusCode: Int,
        contentRangeHeader: String?,
        declaredContentLength: Long,
        expectedStart: Long,
        expectedEndInclusive: Long?,
        sanitizedUrl: String,
    ): ValidatedRangeResponse {
        when (statusCode) {
            206 -> Unit
            200 -> throw WebDavException.RangeNotSupported()
            else -> throw httpStatus(statusCode, "Range GET", sanitizedUrl)
        }

        val parsed = parseContentRange(contentRangeHeader)
            ?: throw invalidRange("Missing or invalid Content-Range header: ${contentRangeHeader ?: "<null>"}")
        if (parsed.endInclusive < parsed.start) {
            throw invalidRange("Content-Range end is before start")
        }
        if (parsed.totalSize >= 0 && parsed.endInclusive >= parsed.totalSize) {
            throw invalidRange(
                "Content-Range end ${parsed.endInclusive} is outside total size ${parsed.totalSize}",
            )
        }
        if (parsed.start != expectedStart) {
            throw invalidRange("Expected Content-Range start $expectedStart but got ${parsed.start}")
        }
        if (expectedEndInclusive != null && parsed.endInclusive != expectedEndInclusive) {
            throw invalidRange(
                "Expected Content-Range end $expectedEndInclusive but got ${parsed.endInclusive}",
            )
        }

        val rangeSpan = parsed.endInclusive - parsed.start
        if (rangeSpan == Long.MAX_VALUE) {
            throw invalidRange("Content-Range length exceeds supported size")
        }
        val expectedContentLength = rangeSpan + 1L
        if (declaredContentLength >= 0 && declaredContentLength != expectedContentLength) {
            throw invalidRange(
                "Expected response body length $expectedContentLength but got $declaredContentLength",
            )
        }
        return ValidatedRangeResponse(
            contentRange = parsed,
            contentLength = declaredContentLength.takeIf { it >= 0L } ?: expectedContentLength,
        )
    }

    private fun parseContentRange(header: String?): ContentRange? {
        val value = header ?: return null
        val match = contentRangePattern.matchEntire(value.lowercase(Locale.US)) ?: return null
        val start = match.groupValues[1].toLongOrNull() ?: return null
        val endInclusive = match.groupValues[2].toLongOrNull() ?: return null
        val totalSize = match.groupValues[3]
            .takeIf { it != "*" }
            ?.toLongOrNull()
            ?: if (match.groupValues[3] == "*") -1L else return null
        return ContentRange(start, endInclusive, totalSize)
    }

    private fun httpStatus(
        statusCode: Int,
        operation: String,
        sanitizedUrl: String,
    ): WebDavException.HttpStatus =
        WebDavException.HttpStatus(
            statusCode,
            "$operation failed with HTTP $statusCode: $sanitizedUrl",
        )

    private fun invalidRange(message: String): WebDavException.InvalidContentRange =
        WebDavException.InvalidContentRange(message)
}
