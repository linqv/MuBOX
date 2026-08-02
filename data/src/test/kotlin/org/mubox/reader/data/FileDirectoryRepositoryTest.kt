package org.mubox.reader.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import org.mubox.reader.data.database.AppDatabase
import org.mubox.reader.data.filedirectory.FileDirectoryCredentialMigrator
import org.mubox.reader.data.filedirectory.FileDirectoryRepository
import org.mubox.reader.data.filedirectory.FileDirectorySourceEntity
import org.mubox.reader.core.model.source.FileDirectorySourceType
import org.mubox.reader.security.CredentialCipher
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class FileDirectoryRepositoryTest {
    @get:Rule
    val temp = TemporaryFolder()

    private lateinit var database: AppDatabase
    private lateinit var repository: FileDirectoryRepository

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AppDatabase::class.java,
        ).build()
        repository = FileDirectoryRepository(
            dao = database.fileDirectoryDao(),
            clock = { 1_700_000_000_000L },
        )
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun addLocalDirectoryStoresManualDirectorySource() = runTest {
        val id = repository.addLocalDirectory(
            displayName = "Comics",
            treeUri = "content://tree/comics",
        )

        val sources = repository.observeSources().first()

        assertTrue(id > 0L)
        assertEquals(1, sources.size)
        val source = sources.single()
        assertEquals(id, source.id)
        assertEquals("Comics", source.displayName)
        assertEquals(FileDirectorySourceType.LOCAL, source.sourceType)
        assertEquals("content://tree/comics", source.localTreeUri)
        assertNull(source.webDavAccountId)
        assertNull(source.webDavPath)
        assertEquals(1_700_000_000_000L, source.addedAt)
    }

    @Test
    fun addWebDavDirectoryStoresManualDirectorySource() = runTest {
        val id = repository.addWebDavDirectory(
            displayName = "/manga",
            accountId = "https://example.test/dav|lin",
            path = "/manga",
        )

        val source = repository.observeSources().first().single()

        assertEquals(id, source.id)
        assertEquals("/manga", source.displayName)
        assertEquals(FileDirectorySourceType.WEBDAV, source.sourceType)
        assertNull(source.localTreeUri)
        assertEquals("https://example.test/dav|lin", source.webDavAccountId)
        assertEquals("/manga", source.webDavPath)
        assertNull(source.webDavBaseUrl)
        assertNull(source.webDavUsername)
        assertNull(source.webDavPassword)
    }

    @Test
    fun observeSourcesReturnsLegacyRowsWithoutDecryptingOrWritingThem() = runTest {
        val id = database.fileDirectoryDao().insertSource(
            FileDirectorySourceEntity(
                displayName = "/manga",
                sourceType = FileDirectorySourceType.WEBDAV,
                webDavAccountId = "https://example.test/dav|lin",
                webDavPath = "/manga",
                webDavBaseUrl = "https://example.test/dav",
                webDavUsername = "lin",
                webDavPassword = "v1:legacy-ciphertext",
                addedAt = 1L,
            ),
        )

        val source = repository.observeSources().first().single()

        assertEquals(id, source.id)
        assertEquals("https://example.test/dav", source.webDavBaseUrl)
        assertEquals("lin", source.webDavUsername)
        assertEquals("v1:legacy-ciphertext", source.webDavPassword)
        assertEquals(
            "v1:legacy-ciphertext",
            database.fileDirectoryDao().getSourcesWithLegacyCredentials().single().webDavPassword,
        )
    }

    @Test
    fun deleteSourceRemovesSavedSource() = runTest {
        val id = repository.addLocalDirectory(
            displayName = "Comics",
            treeUri = "content://tree/comics",
        )

        repository.deleteSource(id)

        assertTrue(repository.observeSources().first().isEmpty())
    }

    @Test
    fun updateWebDavDirectoryStoresOnlyTheAccountReferenceAndClearsLegacyCredentials() = runTest {
        val id = database.fileDirectoryDao().insertSource(
            FileDirectorySourceEntity(
                displayName = "/manga",
                sourceType = FileDirectorySourceType.WEBDAV,
                webDavAccountId = "https://example.test/dav|lin",
                webDavPath = "/manga",
                webDavBaseUrl = "https://example.test/dav",
                webDavUsername = "lin",
                webDavPassword = "old",
                addedAt = 1L,
            ),
        )

        repository.updateWebDavDirectory(
            id = id,
            displayName = "漫画库",
            accountId = "https://cloud.example.test:8443/webdav|alex",
            path = "/books/",
        )

        val source = repository.observeSources().first().single()
        assertEquals(id, source.id)
        assertEquals("漫画库", source.displayName)
        assertEquals("https://cloud.example.test:8443/webdav|alex", source.webDavAccountId)
        assertEquals("/books/", source.webDavPath)
        assertNull(source.webDavBaseUrl)
        assertNull(source.webDavUsername)
        assertNull(source.webDavPassword)
    }

    @Test
    fun migratesLegacyCredentialsToAccountStoreThenClearsDirectoryColumns() = runTest {
        val cipher = TestCredentialCipher()
        val accountStore = WebDavAccountStore(
            dataStore("legacy-directory-success.preferences_pb"),
            cipher,
        )
        val id = insertLegacyWebDavSource(password = "v1:secret")
        val migrator = FileDirectoryCredentialMigrator(
            dao = database.fileDirectoryDao(),
            accountStore = accountStore,
            cipher = cipher,
        )

        val result = migrator.migrateLegacyCredentials()

        assertEquals(listOf(id), result.migratedSourceIds)
        assertTrue(result.failedSourceIds.isEmpty())
        assertEquals(
            SavedWebDavAccount(
                accountId = "legacy-account-id",
                baseUrl = "https://example.test/dav",
                username = "lin",
                password = "secret",
            ),
            accountStore.loadAccount("legacy-account-id"),
        )
        val source = repository.observeSources().first().single()
        assertEquals("legacy-account-id", source.webDavAccountId)
        assertNull(source.webDavBaseUrl)
        assertNull(source.webDavUsername)
        assertNull(source.webDavPassword)
    }

    @Test
    fun failedLegacyCredentialMigrationLeavesRowForRetry() = runTest {
        val cipher = TestCredentialCipher(failEncryption = true)
        val accountStore = WebDavAccountStore(
            dataStore("legacy-directory-retry.preferences_pb"),
            cipher,
        )
        val id = insertLegacyWebDavSource(password = "secret")
        val migrator = FileDirectoryCredentialMigrator(
            dao = database.fileDirectoryDao(),
            accountStore = accountStore,
            cipher = cipher,
        )

        val failed = migrator.migrateLegacyCredentials()

        assertEquals(listOf(id), failed.failedSourceIds)
        assertEquals("secret", repository.observeSources().first().single().webDavPassword)
        assertNull(accountStore.loadAccount("legacy-account-id"))

        cipher.failEncryption = false
        val retried = migrator.migrateLegacyCredentials()

        assertEquals(listOf(id), retried.migratedSourceIds)
        assertTrue(retried.failedSourceIds.isEmpty())
        assertEquals("secret", accountStore.loadAccount("legacy-account-id")?.password)
        assertNull(repository.observeSources().first().single().webDavPassword)
    }

    @Test
    fun legacyMigrationDoesNotOverwriteAnExistingUnifiedAccount() = runTest {
        val cipher = TestCredentialCipher()
        val accountStore = WebDavAccountStore(
            dataStore("legacy-directory-existing-account.preferences_pb"),
            cipher,
        )
        accountStore.saveAccountForId(
            accountId = "legacy-account-id",
            baseUrl = "https://current.example.test/dav",
            username = "current-user",
            password = "current-password",
        )
        val id = insertLegacyWebDavSource(password = "stale-password")
        val migrator = FileDirectoryCredentialMigrator(
            dao = database.fileDirectoryDao(),
            accountStore = accountStore,
            cipher = cipher,
        )

        val result = migrator.migrateLegacyCredentials()

        assertEquals(listOf(id), result.migratedSourceIds)
        assertEquals(
            SavedWebDavAccount(
                accountId = "legacy-account-id",
                baseUrl = "https://current.example.test/dav",
                username = "current-user",
                password = "current-password",
            ),
            accountStore.loadAccount("legacy-account-id"),
        )
        assertNull(repository.observeSources().first().single().webDavPassword)
    }

    private suspend fun insertLegacyWebDavSource(password: String): Long {
        return database.fileDirectoryDao().insertSource(
            FileDirectorySourceEntity(
                displayName = "Legacy",
                sourceType = FileDirectorySourceType.WEBDAV,
                webDavAccountId = "legacy-account-id",
                webDavPath = "/manga",
                webDavBaseUrl = " https://example.test/dav ",
                webDavUsername = "lin",
                webDavPassword = password,
                addedAt = 1L,
            ),
        )
    }

    private fun TestScope.dataStore(fileName: String): DataStore<Preferences> {
        return PreferenceDataStoreFactory.create(
            scope = backgroundScope,
            produceFile = { temp.newFile(fileName) },
        )
    }

    private class TestCredentialCipher(
        var failEncryption: Boolean = false,
    ) : CredentialCipher {
        override fun encrypt(plainText: String): String {
            if (failEncryption) error("encryption unavailable")
            return "v1:$plainText"
        }

        override fun decrypt(storedValue: String): String = storedValue.removePrefix("v1:")
    }
}
