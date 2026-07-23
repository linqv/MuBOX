package com.example.comicdav.core.ports

import androidx.annotation.WorkerThread
import java.io.Closeable
import java.io.File

interface ComicReaderSession : Closeable {
    val pageCount: Int
    val forwardPrefetchPageCount: Int
        get() = 4
    val backwardPrefetchPageCount: Int
        get() = 1
    val advancePrefetchOnPageDemand: Boolean
        get() = false

    @WorkerThread
    fun loadPageToFile(pageIndex: Int, outputFile: File): File

    @WorkerThread
    fun updateViewport(pageIndex: Int, networkClass: Int) = Unit

    fun diagnostics(): String = ""

    @WorkerThread
    fun plannedRanges(pageIndex: Int, networkClass: Int): List<PlannedRemoteRange> = emptyList()

    @WorkerThread
    fun prefetchRange(start: Long, endInclusive: Long): Boolean = false

    @WorkerThread
    fun prefetchRange(
        start: Long,
        endInclusive: Long,
        priority: Int,
        protectedRanges: List<LongRange>,
    ): Boolean = prefetchRange(start, endInclusive)

    fun cancelPrefetches() = Unit
}

data class PlannedRemoteRange(
    val start: Long,
    val endInclusive: Long,
    val pages: List<Int>,
    val priority: Int,
)
