package org.mubox.reader.video.proxy

import java.security.MessageDigest

/** Credential-safe identifiers shared by proxy exception messages. */
internal object VideoProxyDiagnostics {
    fun redactedStreamId(raw: String): String = "stream:${shortHash(raw)}"

    private fun shortHash(raw: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(raw.toByteArray(Charsets.UTF_8))
        return digest.take(6).joinToString("") { byte -> "%02x".format(byte) }
    }
}
