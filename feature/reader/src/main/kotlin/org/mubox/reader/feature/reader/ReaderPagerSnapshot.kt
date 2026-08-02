package org.mubox.reader.feature.reader

data class ReaderPagerSnapshot(
    val currentPage: Int,
    val settledPage: Int,
    val targetPage: Int,
    val offsetFraction: Float,
    val isScrollInProgress: Boolean,
    val uiCurrentPage: Int,
    val pageCount: Int,
)
