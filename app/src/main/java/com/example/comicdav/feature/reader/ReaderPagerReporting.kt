package com.example.comicdav.feature.reader

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull

internal fun reportableSettledPage(currentPage: Int, settledPage: Int): Int? {
    return if (currentPage == settledPage) settledPage else null
}

internal fun Flow<Int?>.reportableReaderPageChanges(): Flow<Int> {
    return filterNotNull().distinctUntilChanged()
}
