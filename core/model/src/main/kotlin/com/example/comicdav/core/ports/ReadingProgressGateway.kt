package com.example.comicdav.core.ports

interface ReadingProgressGateway {
    suspend fun savePage(comicKey: String, pageIndex: Int)
    suspend fun loadPage(comicKey: String): Int
}
