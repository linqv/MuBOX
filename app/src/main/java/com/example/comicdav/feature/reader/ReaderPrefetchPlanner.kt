package com.example.comicdav.feature.reader

internal object ReaderPrefetchPlanner {
    const val FORWARD_PAGES = 4

    fun neighborPrefetchPages(pageIndex: Int, pageCount: Int): List<Int> {
        val forwardPages = (1..FORWARD_PAGES).map { pageIndex + it }
        return (forwardPages + (pageIndex - 1))
            .filter { it in 0 until pageCount }
            .distinct()
    }

    fun desiredPageWindow(pageIndex: Int, pageCount: Int): Set<Int> =
        (listOf(pageIndex) + neighborPrefetchPages(pageIndex, pageCount))
            .filter { it in 0 until pageCount }
            .toSet()
}
