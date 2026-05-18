package com.example.comicdav.feature.library

import com.example.comicdav.data.library.LibraryCatalog
import com.example.comicdav.data.library.LibraryItemWithSources
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LibraryViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
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
    }

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
