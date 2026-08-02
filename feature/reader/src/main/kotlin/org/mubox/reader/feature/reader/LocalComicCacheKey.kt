package org.mubox.reader.feature.reader

import java.security.MessageDigest

fun localComicCacheKey(
    prefix: String,
    stableId: String,
    size: Long?,
    lastModified: Long?,
): String {
    val source = listOf(
        prefix,
        stableId,
        size?.toString().orEmpty(),
        lastModified?.toString().orEmpty(),
    ).joinToString(separator = "\u001F")
    return "$prefix-${source.sha256()}"
}

private fun String.sha256(): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(toByteArray(Charsets.UTF_8))
    return digest.joinToString(separator = "") { byte -> "%02x".format(byte) }
}
