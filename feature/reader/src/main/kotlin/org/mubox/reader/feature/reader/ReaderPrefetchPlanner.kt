package org.mubox.reader.feature.reader

internal object ReaderPrefetchPlanner {
    const val FORWARD_PAGES = 4
    const val BACKWARD_PAGES = 3

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

    fun continuousViewportPlan(
        visiblePages: List<Int>,
        pageCount: Int,
        forwardPages: Int,
        backwardPages: Int,
        direction: Int,
    ): ContinuousViewportPlan? {
        val visible = visiblePages.filter { it in 0 until pageCount }.distinct().sorted()
        if (visible.isEmpty()) return null
        val focus = if (direction < 0) visible.first() else visible.last()
        val forward = forwardPages.coerceAtLeast(0)
        val backward = backwardPages.coerceAtLeast(0)
        val first = (visible.first() - backward).coerceAtLeast(0)
        val last = (visible.last() + forward).coerceAtMost(pageCount - 1)
        val pages = if (direction < 0) {
            (focus downTo first).toList() + ((focus + 1)..last).toList()
        } else {
            (focus..last).toList() + ((focus - 1) downTo first).toList()
        }
        return ContinuousViewportPlan(
            focusPage = focus,
            visiblePages = visible,
            desiredPages = pages.distinct(),
            retentionWindow = (first..last).toSet(),
        )
    }
}

internal data class ContinuousViewportPlan(
    val focusPage: Int,
    val visiblePages: List<Int>,
    val desiredPages: List<Int>,
    val retentionWindow: Set<Int>,
)
