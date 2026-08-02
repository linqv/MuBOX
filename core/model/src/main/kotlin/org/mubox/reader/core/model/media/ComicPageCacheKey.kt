package org.mubox.reader.core.model.media

fun readerImageFormatCacheKey(comicKey: String, avifImagesEnabled: Boolean): String =
    if (avifImagesEnabled) "$comicKey-avif" else comicKey
