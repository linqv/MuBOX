package org.mubox.reader.core.ports

import java.io.File

typealias RemoteRangeComicSessionFactory = (
    fileId: Long,
    size: Long,
    cacheDir: File,
    comicKey: String,
    validator: String,
    avifImagesEnabled: Boolean,
    webDavPrefetchPageCount: Int,
) -> ComicReaderSession
