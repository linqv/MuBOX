package com.example.comicdav.network

import com.example.comicdav.data.SavedWebDavAccount
import com.example.comicdav.data.WebDavAccountStore
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Resolves account credentials without mutating any browser or navigation state. */
class WebDavClientProvider internal constructor(
    private val loadAccount: suspend (String) -> SavedWebDavAccount?,
    private val createClient: (SavedWebDavAccount) -> WebDavClient,
    private val ioDispatcher: CoroutineDispatcher,
) {
    constructor(
        accountStore: WebDavAccountStore,
        ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    ) : this(
        loadAccount = accountStore::loadAccount,
        createClient = { account ->
            OkHttpWebDavClient(
                baseUrl = account.baseUrl,
                username = account.username,
                password = account.password,
            )
        },
        ioDispatcher = ioDispatcher,
    )

    suspend fun clientFor(accountId: String): WebDavClient? = withContext(ioDispatcher) {
        loadAccount(accountId)?.let(createClient)
    }
}
