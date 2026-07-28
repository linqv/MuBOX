package com.example.comicdav.ui

import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

fun decodeWebDavPathForDisplay(path: String): String {
    if (!path.contains('%')) return path

    val output = StringBuilder(path.length)
    val bytes = mutableListOf<Byte>()

    fun flushBytes(): Boolean {
        if (bytes.isEmpty()) return true
        val decoder = StandardCharsets.UTF_8
            .newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        val decoded = runCatching {
            decoder.decode(ByteBuffer.wrap(bytes.toByteArray())).toString()
        }.getOrElse {
            return false
        }
        output.append(decoded)
        bytes.clear()
        return true
    }

    var index = 0
    while (index < path.length) {
        if (path[index] == '%') {
            if (index + 2 >= path.length) return path
            val high = path[index + 1].digitToIntOrNull(16) ?: return path
            val low = path[index + 2].digitToIntOrNull(16) ?: return path
            bytes += ((high shl 4) + low).toByte()
            index += 3
        } else {
            if (!flushBytes()) return path
            output.append(path[index])
            index += 1
        }
    }

    if (!flushBytes()) return path
    return output.toString()
}
