package com.example.comicdav.nativebridge

interface ComicNativeFacade {
    fun openLocal(path: String): Long
    fun openRemote(fileId: Long, size: Long, cacheDir: String): Long
    fun pageCount(handle: Long): Int
    fun loadPageToFile(handle: Long, pageIndex: Int, outputPath: String): Int
    fun close(handle: Long)
    fun lastErrorMessage(): String
}

object ComicNative : ComicNativeFacade {
    init {
        System.loadLibrary("comic_core")
    }

    external override fun openLocal(path: String): Long

    external override fun openRemote(fileId: Long, size: Long, cacheDir: String): Long

    external override fun pageCount(handle: Long): Int

    external override fun loadPageToFile(handle: Long, pageIndex: Int, outputPath: String): Int

    external override fun close(handle: Long)

    external override fun lastErrorMessage(): String
}
