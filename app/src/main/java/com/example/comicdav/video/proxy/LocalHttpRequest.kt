package com.example.comicdav.video.proxy

internal data class LocalHttpRequest(
    val method: String,
    val path: String,
    val version: String,
    val headers: Map<String, String>,
) {
    val allowsPersistentConnection: Boolean
        get() {
            val tokens = connectionTokens()
            return when {
                version.equals("HTTP/1.1", ignoreCase = true) -> "close" !in tokens
                version.equals("HTTP/1.0", ignoreCase = true) -> "keep-alive" in tokens
                else -> false
            }
        }

    fun header(name: String): String? =
        headers[name.lowercase()]

    private fun connectionTokens(): Set<String> =
        header("Connection")
            ?.split(',')
            ?.map { it.trim().lowercase() }
            ?.filter { it.isNotEmpty() }
            ?.toSet()
            ?: emptySet()

    companion object {
        fun parse(headerBlock: String): LocalHttpRequest? {
            val lines = headerBlock
                .lineSequence()
                .map { it.trimEnd('\r') }
                .toList()
            val requestLine = lines.firstOrNull()?.trim().orEmpty()
            val parts = WHITESPACE.split(requestLine, limit = 3)
            if (parts.size < 2 || parts[0].isBlank() || parts[1].isBlank()) return null
            val headers = linkedMapOf<String, String>()
            lines.drop(1).forEach { line ->
                if (line.isEmpty()) return@forEach
                val separator = line.indexOf(':')
                if (separator > 0) {
                    headers[line.substring(0, separator).trim().lowercase()] =
                        line.substring(separator + 1).trim()
                }
            }
            return LocalHttpRequest(
                method = parts[0],
                path = parts[1],
                version = parts.getOrElse(2) { "HTTP/1.0" },
                headers = headers,
            )
        }

        private val WHITESPACE = Regex("\\s+")
    }
}
