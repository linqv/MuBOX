package org.mubox.reader.video.proxy

import java.io.InputStream
import java.io.OutputStream

internal class LocalHttpResponseWriter(
    private val output: OutputStream,
) {
    fun write(
        code: Int,
        headers: Map<String, String> = emptyMap(),
        contentLength: Long? = null,
        contentRange: String? = null,
        connection: LocalHttpConnection = LocalHttpConnection.CLOSE,
        body: InputStream? = null,
    ): LocalHttpBodyWriteResult {
        val builder = StringBuilder().append("HTTP/1.1 $code ${reasonPhrase(code)}\r\n")
        headers.forEach { (name, value) ->
            if (!RESERVED_HEADERS.any { it.equals(name, ignoreCase = true) }) {
                builder.append("$name: $value\r\n")
            }
        }
        contentLength?.let { builder.append("Content-Length: $it\r\n") }
        contentRange?.let { builder.append("Content-Range: $it\r\n") }
        builder.append("Connection: ${connection.headerValue}\r\n")
        builder.append("\r\n")
        output.write(builder.toString().toByteArray(Charsets.ISO_8859_1))
        val bodyResult = writeBody(
            body = body,
            contentLength = contentLength,
            verifyEndOfBody = connection == LocalHttpConnection.KEEP_ALIVE,
        )
        output.flush()
        return bodyResult
    }

    private fun writeBody(
        body: InputStream?,
        contentLength: Long?,
        verifyEndOfBody: Boolean,
    ): LocalHttpBodyWriteResult {
        if (body == null) return LocalHttpBodyWriteResult.COMPLETE
        if (contentLength == null) {
            body.copyTo(output)
            return LocalHttpBodyWriteResult.COMPLETE
        }
        var remaining = contentLength
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (remaining > 0L) {
            val count = body.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
            if (count == -1) {
                return LocalHttpBodyWriteResult.LENGTH_MISMATCH
            }
            output.write(buffer, 0, count)
            remaining -= count.toLong()
        }
        if (verifyEndOfBody && body.read() != -1) {
            return LocalHttpBodyWriteResult.LENGTH_MISMATCH
        }
        return LocalHttpBodyWriteResult.COMPLETE
    }

    private fun reasonPhrase(code: Int): String =
        when (code) {
            200 -> "OK"
            206 -> "Partial Content"
            404 -> "Not Found"
            405 -> "Method Not Allowed"
            416 -> "Range Not Satisfiable"
            431 -> "Request Header Fields Too Large"
            502 -> "Bad Gateway"
            else -> "OK"
        }

    private companion object {
        val RESERVED_HEADERS = setOf("Content-Length", "Content-Range", "Connection")
    }
}

internal enum class LocalHttpConnection(
    val headerValue: String,
) {
    KEEP_ALIVE("keep-alive"),
    CLOSE("close"),
}

internal enum class LocalHttpBodyWriteResult {
    COMPLETE,
    LENGTH_MISMATCH,
}
