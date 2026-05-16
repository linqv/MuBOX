package com.example.comicdav.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.comicdav.data.filedirectory.FileDirectoryRepository
import com.example.comicdav.data.filedirectory.FileDirectorySourceType
import com.example.comicdav.data.library.LibraryDatabase
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class FileDirectoryRepositoryTest {
    private lateinit var database: LibraryDatabase
    private lateinit var repository: FileDirectoryRepository

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            LibraryDatabase::class.java,
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
    }

    @Test
    fun addWebDavDirectoryStoresConnectionDetails() = runTest {
        val id = repository.addWebDavDirectory(
            displayName = "/manga",
            accountId = "https://example.test/dav|lin",
            path = "/manga",
            baseUrl = "https://example.test/dav",
            username = "lin",
            password = "secret",
        )

        val source = repository.observeSources().first().single()

        assertEquals(id, source.id)
        assertEquals("https://example.test/dav", source.webDavBaseUrl)
        assertEquals("lin", source.webDavUsername)
        assertEquals("secret", source.webDavPassword)
        assertEquals("/manga", source.webDavPath)
        assertEquals("https://example.test/dav|lin", source.webDavAccountId)
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
}
