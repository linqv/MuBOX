package org.mubox.reader.feature.library

import org.mubox.reader.MainDispatcherRule
import org.mubox.reader.core.ports.LibraryCatalog
import org.mubox.reader.core.model.library.LibraryItemWithSources
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LibraryViewModelTest {
    @get:Rule
    val mainDispatcher = MainDispatcherRule()

    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        mainDispatcher.set(dispatcher)
    }

    @Test
    fun emptyLibraryStopsLoadingAfterInitialCollection() = runTest(dispatcher) {
        val catalog = FakeLibraryCatalog()
        val viewModel = LibraryViewModel(catalog)

        advanceUntilIdle()

        assertFalse(viewModel.uiState.isLoading)
        assertEquals(emptyList<LibraryItemWithSources>(), viewModel.uiState.items)
    }

    @Test
    fun addLocalComicStoresUriAndShowsMessage() = runTest(dispatcher) {
        val catalog = FakeLibraryCatalog()
        val viewModel = LibraryViewModel(catalog)

        viewModel.addLocalComic(
            uri = "content://local/book.cbz",
            fileName = "book.cbz",
            size = 123L,
            lastModified = 456L,
        )
        advanceUntilIdle()

        assertEquals("content://local/book.cbz", catalog.localAdds.single().uri)
        assertEquals("已将 book.cbz 加入书架", viewModel.uiState.message)
    }

    @Test
    fun addWebDavComicStoresRemoteMetadataAndShowsMessage() = runTest(dispatcher) {
        val catalog = FakeLibraryCatalog()
        val viewModel = LibraryViewModel(catalog)

        viewModel.addWebDavComic(
            accountId = "https://example.test|lin",
            remotePath = "/books/book.cbz",
            fileName = "book.cbz",
            size = 123L,
            etag = "\"abc\"",
            lastModified = 456L,
        )
        advanceUntilIdle()

        val add = catalog.webDavAdds.single()
        assertEquals("/books/book.cbz", add.remotePath)
        assertEquals("\"abc\"", add.etag)
        assertEquals("已将 book.cbz 加入书架", viewModel.uiState.message)
    }

    private class FakeLibraryCatalog : LibraryCatalog {
        private val items = MutableStateFlow<List<LibraryItemWithSources>>(emptyList())
        val localAdds = mutableListOf<LocalAdd>()
        val webDavAdds = mutableListOf<WebDavAdd>()

        override fun observeLibrary(): Flow<List<LibraryItemWithSources>> = items

        override suspend fun addLocalComic(
            uri: String,
            fileName: String,
            size: Long?,
            lastModified: Long?,
        ): Long {
            localAdds += LocalAdd(uri, fileName, size, lastModified)
            return localAdds.size.toLong()
        }

        override suspend fun addWebDavComic(
            accountId: String,
            remotePath: String,
            fileName: String,
            size: Long?,
            etag: String?,
            lastModified: Long?,
            cacheKey: String?,
            coverPath: String?,
        ): Long {
            webDavAdds += WebDavAdd(accountId, remotePath, fileName, size, etag, lastModified, cacheKey, coverPath)
            return webDavAdds.size.toLong()
        }

        override suspend fun markOpened(libraryItemId: Long) = Unit

        val coverPathUpdates = mutableListOf<CoverPathUpdate>()

        override suspend fun updateCoverPath(libraryItemId: Long, coverPath: String?) {
            coverPathUpdates += CoverPathUpdate(libraryItemId, coverPath)
        }

        override suspend fun removeComic(libraryItemId: Long) = Unit
    }

    private data class CoverPathUpdate(
        val libraryItemId: Long,
        val coverPath: String?,
    )

    private data class LocalAdd(
        val uri: String,
        val fileName: String,
        val size: Long?,
        val lastModified: Long?,
    )

    private data class WebDavAdd(
        val accountId: String,
        val remotePath: String,
        val fileName: String,
        val size: Long?,
        val etag: String?,
        val lastModified: Long?,
        val cacheKey: String?,
        val coverPath: String?,
    )
}
