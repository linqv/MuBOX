package org.mubox.reader.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import org.mubox.reader.data.database.AppDatabase
import org.mubox.reader.data.library.LibraryRepository
import org.mubox.reader.core.model.library.OfflineState
import org.mubox.reader.core.model.library.SourceType
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
    private lateinit var database: AppDatabase
    private lateinit var repository: LibraryRepository

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AppDatabase::class.java,
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
            coverPath = "/cache/library-covers/chapter-01.img",
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
        assertEquals("/cache/library-covers/chapter-01.img", itemWithSources.item.coverPath)
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
    fun updateCoverPathUpdatesExistingCoverPathAndAllowsNull() = runTest {
        val libraryItemId = repository.addWebDavComic(
            accountId = "primary-webdav",
            remotePath = "/library/series/Chapter 01.zip",
            fileName = "Chapter 01.zip",
            size = 84_000L,
            etag = "\"abc123\"",
            lastModified = 1_650_000_000_000L,
            cacheKey = "primary-webdav:/library/series/Chapter 01.zip",
            coverPath = "/cache/library-covers/v1.img",
        )

        assertEquals("/cache/library-covers/v1.img", repository.observeLibrary().first().single().item.coverPath)

        repository.updateCoverPath(libraryItemId, "/cache/library-covers/v2/new.img")
        assertEquals("/cache/library-covers/v2/new.img", repository.observeLibrary().first().single().item.coverPath)

        repository.updateCoverPath(libraryItemId, null)
        assertNull(repository.observeLibrary().first().single().item.coverPath)
    }

    @Test
    fun addLocalComicStoresTitleWithoutArchiveExtension() = runTest {
        val libraryItemId = repository.addLocalComic(
            uri = "content://documents/tree/books/document/book.zip",
            fileName = "book.zip",
            size = 100L,
            lastModified = 10L,
        )

        val library = repository.observeLibrary().first()

        assertTrue(libraryItemId > 0L)
        assertEquals("book", library.single().item.title)
        assertEquals("book", library.single().item.displayName)
        assertEquals("book.zip", library.single().localSource?.fileName)
    }

    @Test
    fun addLocalComicDoesNotDuplicateExistingUri() = runTest {
        val firstId = repository.addLocalComic(
            uri = "content://documents/tree/comics/document/same.cbz",
            fileName = "same.cbz",
            size = 100L,
            lastModified = 10L,
        )
        val secondId = repository.addLocalComic(
            uri = "content://documents/tree/comics/document/same.cbz",
            fileName = "same-renamed.cbz",
            size = 200L,
            lastModified = 20L,
        )

        val library = repository.observeLibrary().first()

        assertEquals(firstId, secondId)
        assertEquals(1, library.size)
        assertEquals("same.cbz", library.single().localSource?.fileName)
    }

    @Test
    fun addWebDavComicDoesNotDuplicateExistingAccountPath() = runTest {
        val firstId = repository.addWebDavComic(
            accountId = "primary-webdav",
            remotePath = "/library/same.cbz",
            fileName = "same.cbz",
            size = 100L,
            etag = "\"first\"",
            lastModified = 10L,
        )
        val secondId = repository.addWebDavComic(
            accountId = "primary-webdav",
            remotePath = "/library/same.cbz",
            fileName = "same-renamed.cbz",
            size = 200L,
            etag = "\"second\"",
            lastModified = 20L,
        )

        val library = repository.observeLibrary().first()

        assertEquals(firstId, secondId)
        assertEquals(1, library.size)
        assertEquals("same.cbz", library.single().webDavSource?.fileName)
    }

    @Test
    fun removeComicDeletesOnlyTheLibraryEntry() = runTest {
        val removedId = repository.addLocalComic(
            uri = "content://documents/tree/comics/document/remove.cbz",
            fileName = "remove.cbz",
        )
        repository.addLocalComic(
            uri = "content://documents/tree/comics/document/keep.cbz",
            fileName = "keep.cbz",
        )

        repository.removeComic(removedId)

        val library = repository.observeLibrary().first()
        assertEquals(listOf("keep"), library.map { it.item.title })
    }
}
