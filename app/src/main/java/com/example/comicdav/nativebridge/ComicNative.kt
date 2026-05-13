package com.example.comicdav.nativebridge

interface ComicNativeFacade {
    fun openLocal(path: String): Long
    fun openRemote(fileId: Long, size: Long, cacheDir: String, comicKey: String, validator: String): Long
    fun pageCount(handle: Long): Int
    fun loadPageToFile(handle: Long, pageIndex: Int, outputPath: String): Int
    fun updateViewport(handle: Long, pageIndex: Int, networkClass: Int): Int
    fun diagnostics(handle: Long): String
    fun close(handle: Long)
    fun lastErrorMessage(): String
}

object ComicNative : ComicNativeFacade {
    init {
        System.loadLibrary("comic_core")
    }

    external override fun openLocal(path: String): Long

    external override fun openRemote(
        fileId: Long,
        size: Long,
        cacheDir: String,
        comicKey: String,
        validator: String,
    ): Long

    external override fun pageCount(handle: Long): Int

    external override fun loadPageToFile(handle: Long, pageIndex: Int, outputPath: String): Int

    external override fun updateViewport(handle: Long, pageIndex: Int, networkClass: Int): Int

    external override fun diagnostics(handle: Long): String

    external override fun close(handle: Long)

    external override fun lastErrorMessage(): String
}
