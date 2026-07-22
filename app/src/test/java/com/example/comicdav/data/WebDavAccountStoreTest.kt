package com.example.comicdav.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import com.example.comicdav.security.CredentialCipher
import java.util.Collections
import java.util.concurrent.Executors
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

@OptIn(ExperimentalCoroutinesApi::class)
class WebDavAccountStoreTest {
    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun returnsNullWhenAccountHasNotBeenSaved() = runTest {
        val store = WebDavAccountStore(dataStore("webdav-account-empty.preferences_pb"))

        assertNull(store.loadAccount("https://example.test/dav|lin"))
    }

    @Test
    fun savesAndLoadsAccountByStableAccountId() = runTest {
        val dataStore = dataStore("webdav-account.preferences_pb")
        val store = WebDavAccountStore(dataStore)

        val saved = store.saveAccount(
            baseUrl = " https://example.test/dav/ ",
            username = "lin",
            password = "secret",
        )

        assertEquals(
            SavedWebDavAccount(
                accountId = "https://example.test/dav/|lin",
                baseUrl = "https://example.test/dav/",
                username = "lin",
                password = "secret",
            ),
            saved,
        )
        assertEquals(saved, WebDavAccountStore(dataStore).loadAccount(saved.accountId))
    }

    @Test
    fun savesMultipleAccountsWithoutColliding() = runTest {
        val store = WebDavAccountStore(dataStore("webdav-accounts.preferences_pb"))

        val primary = store.saveAccount("https://example.test/dav", "lin", "first")
        val secondary = store.saveAccount("https://example.test/dav", "alex", "second")

        assertEquals(primary, store.loadAccount("https://example.test/dav|lin"))
        assertEquals(secondary, store.loadAccount("https://example.test/dav|alex"))
    }

    @Test
    fun encryptionAndDecryptionRunOnTheCredentialDispatcher() = runTest {
        val cipherThreads = Collections.synchronizedList(mutableListOf<String>())
        val cipher = object : CredentialCipher {
            override fun encrypt(plainText: String): String {
                cipherThreads += Thread.currentThread().name
                return "encrypted:$plainText"
            }

            override fun decrypt(storedValue: String): String {
                cipherThreads += Thread.currentThread().name
                return storedValue.removePrefix("encrypted:")
            }
        }
        val credentialDispatcher = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "credential-io")
        }.asCoroutineDispatcher()
        try {
            val store = WebDavAccountStore(
                dataStore("webdav-account-dispatcher.preferences_pb"),
                cipher,
                credentialDispatcher,
            )

            val saved = store.saveAccount("https://example.test/dav", "lin", "secret")
            assertEquals(saved, store.loadAccount(saved.accountId))

            assertEquals(listOf("credential-io", "credential-io"), cipherThreads)
        } finally {
            credentialDispatcher.close()
        }
    }

    private fun TestScope.dataStore(fileName: String): DataStore<Preferences> {
        return PreferenceDataStoreFactory.create(
            scope = backgroundScope,
            produceFile = { temp.newFile(fileName) },
        )
    }
}
