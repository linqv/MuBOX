package org.mubox.reader.feature.reader

internal data class ReaderContinuousViewportPosition(
    val firstVisiblePage: Int,
    val firstVisibleOffset: Int,
)

/** Page-index direction: -1 returns to earlier pages, +1 advances to later pages. */
internal fun readerContinuousScrollDirection(
    previous: ReaderContinuousViewportPosition?,
    current: ReaderContinuousViewportPosition,
    isScrollInProgress: Boolean,
): Int {
    if (!isScrollInProgress || previous == null) return 0
    return when {
        current.firstVisiblePage > previous.firstVisiblePage -> 1
        current.firstVisiblePage < previous.firstVisiblePage -> -1
        current.firstVisibleOffset > previous.firstVisibleOffset -> 1
        current.firstVisibleOffset < previous.firstVisibleOffset -> -1
        else -> 0
    }
}
