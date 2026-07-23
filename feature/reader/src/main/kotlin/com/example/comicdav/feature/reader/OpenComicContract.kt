package com.example.comicdav.feature.reader

import com.example.comicdav.core.ports.ComicReaderSession
import java.io.File

/** Reader-facing result of preparing a comic session in the app infrastructure layer. */
data class OpenComicResult(
    val comicKey: String,
    val pageCacheKey: String = comicKey,
    val localFile: File,
    val session: ComicReaderSession,
    val initialPage: Int,
)
