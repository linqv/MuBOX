package com.example.comicdav.feature.reader

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull

internal data class ReaderPageDemand(
    val page: Int,
    val source: String,
)

internal fun reportableSettledPage(currentPage: Int, settledPage: Int): Int? {
    return if (currentPage == settledPage) settledPage else null
}

internal fun Flow<Int?>.reportableReaderPageChanges(): Flow<Int> {
    return filterNotNull().distinctUntilChanged()
}

internal fun reportablePagerDemandPages(snapshot: ReaderPagerSnapshot): List<ReaderPageDemand> {
    return listOf(
        ReaderPageDemand(page = snapshot.currentPage, source = "pager_current"),
        ReaderPageDemand(page = snapshot.targetPage, source = "pager_target"),
    ).distinctBy { it.page }
}
