package org.mubox.reader.nativebridge

import androidx.annotation.WorkerThread

interface ComicNativeFacade {
    @WorkerThread fun openLocal(path: String): Long
    @WorkerThread fun openLocalFd(fd: Int, size: Long): Long
    @WorkerThread
    fun openRemoteCachedV1(
        fileId: Long,
        size: Long,
        cacheDir: String,
        comicKey: String,
        validator: String,
    ): Long
    fun pageCount(handle: Long): Int
    @WorkerThread fun loadPageToFile(handle: Long, pageIndex: Int, outputPath: String): Int
    @WorkerThread fun updateViewport(handle: Long, pageIndex: Int, networkClass: Int, forwardPrefetchPageCount: Int): Int
    fun diagnostics(handle: Long): String
    @WorkerThread fun plannedRanges(handle: Long, pageIndex: Int, networkClass: Int, forwardPrefetchPageCount: Int): String
    @WorkerThread
    fun reconcilePrefetchPlanV1(
        handle: Long,
        pageIndex: Int,
        networkClass: Int,
        forwardPrefetchPageCount: Int,
        byteBudget: Long,
        activeRanges: LongArray,
        completedRanges: LongArray,
    ): LongArray?
    @WorkerThread
    fun prefetchRemoteRangeV1(
        handle: Long,
        start: Long,
        endInclusive: Long,
        priority: Int,
        protectedRanges: LongArray,
    ): Int
    fun cancelRemoteIoV1(handle: Long)
    fun close(handle: Long)
    fun lastErrorMessage(): String
}

object ComicNative : ComicNativeFacade {
    init {
        System.loadLibrary("comic_core")
    }

    @WorkerThread
    external override fun openLocal(path: String): Long

    @WorkerThread
    external override fun openLocalFd(fd: Int, size: Long): Long

    @WorkerThread
    external override fun openRemoteCachedV1(
        fileId: Long,
        size: Long,
        cacheDir: String,
        comicKey: String,
        validator: String,
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

    @WorkerThread
    external override fun reconcilePrefetchPlanV1(
        handle: Long,
        pageIndex: Int,
        networkClass: Int,
        forwardPrefetchPageCount: Int,
        byteBudget: Long,
        activeRanges: LongArray,
        completedRanges: LongArray,
    ): LongArray?

    @WorkerThread
    external override fun prefetchRemoteRangeV1(
        handle: Long,
        start: Long,
        endInclusive: Long,
        priority: Int,
        protectedRanges: LongArray,
    ): Int

    external override fun cancelRemoteIoV1(handle: Long)

    external override fun close(handle: Long)

    external override fun lastErrorMessage(): String
}
