package org.mubox.reader.core.crypto

import java.security.MessageDigest

fun String.sha256Hex(): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(toByteArray(Charsets.UTF_8))
    return digest.joinToString(separator = "") { byte -> "%02x".format(byte) }
}
