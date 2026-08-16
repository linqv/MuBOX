package org.mubox.reader.video.proxy

data class VideoProxyRuntimeStats(
    val currentRange: String?,
    val remoteHttpStatus: Int?,
    val memoryCacheHits: Long,
    val prefetchState: String?,
    val diagnosticMessage: String?,
)

/** Minimal flat-object JSON decoder for the native statistics ABI. */
internal object VideoProxyRuntimeStatsJson {
    fun decode(encoded: String): VideoProxyRuntimeStats? {
        val trimmed = encoded.trim()
        if (trimmed.isEmpty() || trimmed == "null") return null
        val values = FlatJsonParser(trimmed).parse() ?: return null
        return VideoProxyRuntimeStats(
            currentRange = values["currentRange"],
            remoteHttpStatus = values["remoteHttpStatus"]?.toIntOrNull(),
            memoryCacheHits = values["memoryCacheHits"]?.toLongOrNull() ?: 0L,
            prefetchState = values["prefetchState"],
            diagnosticMessage = values["diagnosticMessage"],
        )
    }

    private class FlatJsonParser(private val source: String) {
        private var index = 0

        fun parse(): Map<String, String?>? {
            skipWhitespace()
            if (!take('{')) return null
            val values = linkedMapOf<String, String?>()
            skipWhitespace()
            if (take('}')) return if (atEnd()) values else null
            while (true) {
                skipWhitespace()
                val key = parseString() ?: return null
                skipWhitespace()
                if (!take(':')) return null
                skipWhitespace()
                val value = parseScalar() ?: return null
                values[key] = value.value
                skipWhitespace()
                when {
                    take('}') -> return if (atEnd()) values else null
                    take(',') -> Unit
                    else -> return null
                }
            }
        }

        private fun parseScalar(): ParsedScalar? = when (source.getOrNull(index)) {
            '"' -> parseString()?.let(::ParsedScalar)
            'n' -> if (takeLiteral("null")) ParsedScalar(null) else null
            '-', in '0'..'9' -> parseNumberToken()?.let(::ParsedScalar)
            else -> null
        }

        private fun parseString(): String? {
            if (!take('"')) return null
            val result = StringBuilder()
            while (index < source.length) {
                when (val char = source[index++]) {
                    '"' -> return result.toString()
                    '\\' -> {
                        val escaped = source.getOrNull(index++) ?: return null
                        when (escaped) {
                            '"', '\\', '/' -> result.append(escaped)
                            'b' -> result.append('\b')
                            'f' -> result.append('\u000c')
                            'n' -> result.append('\n')
                            'r' -> result.append('\r')
                            't' -> result.append('\t')
                            'u' -> {
                                if (index + 4 > source.length) return null
                                val code = source.substring(index, index + 4).toIntOrNull(16) ?: return null
                                result.append(code.toChar())
                                index += 4
                            }
                            else -> return null
                        }
                    }
                    else -> {
                        if (char.code < 0x20) return null
                        result.append(char)
                    }
                }
            }
            return null
        }

        private fun parseNumberToken(): String? {
            val start = index
            if (source.getOrNull(index) == '-') index += 1
            val digitsStart = index
            while (source.getOrNull(index)?.isDigit() == true) index += 1
            if (digitsStart == index) return null
            return source.substring(start, index)
        }

        private fun takeLiteral(value: String): Boolean {
            if (!source.startsWith(value, index)) return false
            index += value.length
            return true
        }

        private fun take(expected: Char): Boolean {
            if (source.getOrNull(index) != expected) return false
            index += 1
            return true
        }

        private fun skipWhitespace() {
            while (source.getOrNull(index)?.isWhitespace() == true) index += 1
        }

        private fun atEnd(): Boolean {
            skipWhitespace()
            return index == source.length
        }

        private data class ParsedScalar(val value: String?)
    }
}
