package com.example.comicdav.network

data class WebDavItem(
    val name: String,
    val path: String,
    val isDirectory: Boolean,
    val size: Long?,
    val etag: String?,
    val lastModified: Long?,
)

data class RemoteFileInfo(
    val path: String,
    val size: Long,
    val etag: String?,
    val lastModified: Long?,
    val supportsRange: Boolean,
)

data class RangeReadResult(
    val bytes: ByteArray,
    val totalSize: Long,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is RangeReadResult) return false
        return totalSize == other.totalSize && bytes.contentEquals(other.bytes)
    }

    override fun hashCode(): Int {
        var result = bytes.contentHashCode()
        result = 31 * result + totalSize.hashCode()
        return result
    }
}

sealed class WebDavException(message: String, cause: Throwable? = null) : Exception(message, cause) {
    class HttpStatus(val statusCode: Int, message: String) : WebDavException(message)
    class MissingMetadata(message: String) : WebDavException(message)
    class RangeNotSupported(message: String = "Server does not support byte range requests") :
        WebDavException(message)
    class InvalidContentRange(message: String) : WebDavException(message)
    class Network(message: String, cause: Throwable? = null) : WebDavException(message, cause)
}
