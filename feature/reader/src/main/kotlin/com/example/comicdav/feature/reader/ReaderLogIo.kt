package com.example.comicdav.feature.reader

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

suspend fun <T> runReaderLogIo(
    dispatcher: CoroutineDispatcher = Dispatchers.IO,
    block: suspend () -> T,
): T = withContext(dispatcher) { block() }
