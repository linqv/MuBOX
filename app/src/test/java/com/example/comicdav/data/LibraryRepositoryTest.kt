package com.example.comicdav.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.comicdav.data.library.LibraryDatabase
import com.example.comicdav.data.library.LibraryRepository
import com.example.comicdav.data.library.OfflineState
import com.example.comicdav.data.library.SourceType
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
class LibraryRepositoryTest {
    private lateinit var database: LibraryDatabase
    private lateinit var repository: LibraryRepository

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            LibraryDatabase::class.java,
        ).build()
        repository = LibraryRepository(
            dao = database.libraryDao(),
            clock = { 1_700_000_000_000L },
        )
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun addLocalComicStoresPersistedUriWithoutCopyingSourceFile() = runTest {
        val libraryItemId = repository.addLocalComic(
            uri = "content://documents/tree/comics/document/manga.cbz",
            fileName = "manga.cbz",
            size = 42_000L,
            lastModified = 1_600_000_000_000L,
        )

        val library = repository.observeLibrary().first()

        assertTrue(libraryItemId > 0L)
        assertEquals(1, library.size)
        val itemWithSources = library.single()
        assertEquals(libraryItemId, itemWithSources.item.id)
        assertEquals("manga", itemWithSources.item.title)
        assertEquals("manga", itemWithSources.item.displayName)
        assertEquals(SourceType.LOCAL, itemWithSources.item.sourceType)
        assertEquals(OfflineState.NOT_DOWNLOADED, itemWithSources.item.offlineState)
        assertEquals(0, itemWithSources.item.lastPageIndex)
        assertEquals(1_700_000_000_000L, itemWithSources.item.addedAt)
        assertNull(itemWithSources.item.lastOpenedAt)
        assertNull(itemWithSources.webDavSource)

        val localSource = itemWithSources.localSource
        require(localSource != null)
        assertEquals(libraryItemId, localSource.libraryItemId)
        assertEquals("content://documents/tree/comics/document/manga.cbz", localSource.uri)
        assertEquals("manga.cbz", localSource.fileName)
        assertEquals(42_000L, localSource.size)
        assertEquals(1_600_000_000_000L, localSource.lastModified)
    }

    @Test
    fun addWebDavComicStoresRemoteIdentityMetadataAndDefaults() = runTest {
        val libraryItemId = repository.addWebDavComic(
            accountId = "primary-webdav",
            remotePath = "/library/series/Chapter 01.zip",
            fileName = "Chapter 01.zip",
            size = 84_000L,
            etag = "\"abc123\"",
            lastModified = 1_650_000_000_000L,
            cacheKey = "primary-webdav:/library/series/Chapter 01.zip",
        )

        val library = repository.observeLibrary().first()

        assertTrue(libraryItemId > 0L)
        assertEquals(1, library.size)
        val itemWithSources = library.single()
        assertEquals(libraryItemId, itemWithSources.item.id)
        assertEquals("Chapter 01", itemWithSources.item.title)
        assertEquals("Chapter 01", itemWithSources.item.displayName)
        assertEquals(SourceType.WEBDAV, itemWithSources.item.sourceType)
        assertEquals(OfflineState.NOT_DOWNLOADED, itemWithSources.item.offlineState)
        assertNull(itemWithSources.localSource)

        val webDavSource = itemWithSources.webDavSource
        require(webDavSource != null)
        assertEquals(libraryItemId, webDavSource.libraryItemId)
        assertEquals("primary-webdav", webDavSource.accountId)
        assertEquals("/library/series/Chapter 01.zip", webDavSource.remotePath)
        assertEquals("Chapter 01.zip", webDavSource.fileName)
        assertEquals(84_000L, webDavSource.size)
        assertEquals("\"abc123\"", webDavSource.etag)
        assertEquals(1_650_000_000_000L, webDavSource.lastModified)
        assertEquals("primary-webdav:/library/series/Chapter 01.zip", webDavSource.cacheKey)
    }

    @Test
    fun addLocalDocumentStoresTitleWithoutMuPdfDocumentExtension() = runTest {
        val libraryItemId = repository.addLocalComic(
            uri = "content://documents/tree/books/document/book.pdf",
            fileName = "book.pdf",
            size = 100L,
            lastModified = 10L,
        )

        val library = repository.observeLibrary().first()

        assertTrue(libraryItemId > 0L)
        assertEquals("book", library.single().item.title)
        assertEquals("book", library.single().item.displayName)
        assertEquals("book.pdf", library.single().localSource?.fileName)
    }

}
