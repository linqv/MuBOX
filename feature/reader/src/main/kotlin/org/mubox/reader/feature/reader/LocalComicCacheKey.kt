package org.mubox.reader.feature.reader

import org.mubox.reader.core.crypto.sha256Hex

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
    return "$prefix-${source.sha256Hex()}"
}
