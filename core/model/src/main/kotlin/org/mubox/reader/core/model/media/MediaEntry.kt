package org.mubox.reader.core.model.media

/** A source-neutral local media entry shared by browsing and playback features. */
data class MediaEntry(
    val name: String,
    val uri: String,
    val isDirectory: Boolean,
    val size: Long? = null,
    val lastModified: Long? = null,
    val mediaKind: MediaKind = mediaKindFor(name = name, isDirectory = isDirectory),
)
