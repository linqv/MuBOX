package com.example.comicdav.feature.reader

internal object ReaderPrefetchPlanner {
    const val FORWARD_PAGES = 4
    const val BACKWARD_PAGES = 1

    fun neighborPrefetchPages(
        pageIndex: Int,
        pageCount: Int,
        forwardPages: Int = FORWARD_PAGES,
        backwardPages: Int = BACKWARD_PAGES,
    ): List<Int> {
        val forwardPageIndexes = (1..forwardPages.coerceAtLeast(0)).map { pageIndex + it }
        val backwardPageIndexes = (1..backwardPages.coerceAtLeast(0)).map { pageIndex - it }
        return (forwardPageIndexes + backwardPageIndexes)
            .filter { it in 0 until pageCount }
            .distinct()
    }

    fun desiredPageWindow(
        pageIndex: Int,
        pageCount: Int,
        forwardPages: Int = FORWARD_PAGES,
        backwardPages: Int = BACKWARD_PAGES,
    ): Set<Int> =
        (listOf(pageIndex) + neighborPrefetchPages(pageIndex, pageCount, forwardPages, backwardPages))
            .filter { it in 0 until pageCount }
            .toSet()
}
