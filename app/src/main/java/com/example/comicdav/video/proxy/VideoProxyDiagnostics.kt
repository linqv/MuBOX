package com.example.comicdav.video.proxy

import java.security.MessageDigest

internal class VideoProxyDiagnostics(
    private val mode: VideoProxyDiagnosticsMode,
    private val sink: (String) -> Unit = { message -> System.err.println(message) },
) {
    fun summary(event: () -> String) {
        if (mode != VideoProxyDiagnosticsMode.OFF) {
            sink("video_proxy ${redactCredentials(event())}")
        }
    }

    fun detail(event: () -> String) {
        if (mode == VideoProxyDiagnosticsMode.DETAIL) {
            sink("video_proxy ${redactCredentials(event())}")
        }
    }

    fun streamId(raw: String): String = "stream:${shortHash(raw)}"

    private fun shortHash(raw: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(raw.toByteArray(Charsets.UTF_8))
        return digest.take(6).joinToString("") { byte -> "%02x".format(byte) }
    }

    private fun redactCredentials(message: String): String =
        message
            .replace(USER_INFO_URL_REGEX, "://<redacted>@")
            .replace(AUTHORIZATION_REGEX) { match -> "${match.groupValues[1]}<redacted>" }
            .replace(SECRET_QUERY_REGEX) { match -> "${match.groupValues[1]}=<redacted>" }

    private companion object {
        private val USER_INFO_URL_REGEX = Regex("://[^/@\\s]+@")
        private val AUTHORIZATION_REGEX = Regex("(?i)(authorization\\s*[:=]\\s*)[^\\s,]+")
        private val SECRET_QUERY_REGEX = Regex("(?i)\\b(password|passwd|token|access_token|refresh_token|secret)=([^\\s&]+)")
    }
}
