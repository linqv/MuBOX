package org.mubox.reader.nativebridge

import androidx.annotation.WorkerThread

interface ComicNativeFacade {
    @WorkerThread fun openLocal(path: String, avifImagesEnabled: Boolean): Long
    @WorkerThread fun openLocalFd(fd: Int, size: Long, format: String, avifImagesEnabled: Boolean): Long
    @WorkerThread fun openRemote(
        fileId: Long,
        size: Long,
        cacheDir: String,
        comicKey: String,
        validator: String,
        avifImagesEnabled: Boolean,
    ): Long
    fun pageCount(handle: Long): Int
    @WorkerThread fun loadPageToFile(handle: Long, pageIndex: Int, outputPath: String): Int
    @WorkerThread fun updateViewport(handle: Long, pageIndex: Int, networkClass: Int, forwardPrefetchPageCount: Int): Int
    fun diagnostics(handle: Long): String
    @WorkerThread fun plannedRanges(handle: Long, pageIndex: Int, networkClass: Int, forwardPrefetchPageCount: Int): String
    fun close(handle: Long)
    fun lastErrorMessage(): String
}

object ComicNative : ComicNativeFacade {
    init {
        System.loadLibrary("comic_core")
    }

    @WorkerThread
    external override fun openLocal(path: String, avifImagesEnabled: Boolean): Long

    @WorkerThread
    external override fun openLocalFd(fd: Int, size: Long, format: String, avifImagesEnabled: Boolean): Long

    @WorkerThread
    external override fun openRemote(
        fileId: Long,
        size: Long,
        cacheDir: String,
        comicKey: String,
        validator: String,
        avifImagesEnabled: Boolean,
    ): Long

    external override fun pageCount(handle: Long): Int

    @WorkerThread
    external override fun loadPageToFile(handle: Long, pageIndex: Int, outputPath: String): Int

    @WorkerThread
    external override fun updateViewport(
        handle: Long,
        pageIndex: Int,
        networkClass: Int,
        forwardPrefetchPageCount: Int,
    ): Int

    external override fun diagnostics(handle: Long): String

    @WorkerThread
    external override fun plannedRanges(
        handle: Long,
        pageIndex: Int,
        networkClass: Int,
        forwardPrefetchPageCount: Int,
    ): String

    external override fun close(handle: Long)

    external override fun lastErrorMessage(): String
}
