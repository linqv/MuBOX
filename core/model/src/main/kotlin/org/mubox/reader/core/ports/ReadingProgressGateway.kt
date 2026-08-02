package org.mubox.reader.core.ports

interface ReadingProgressGateway {
    suspend fun savePage(comicKey: String, pageIndex: Int)
    suspend fun loadPage(comicKey: String): Int
}
