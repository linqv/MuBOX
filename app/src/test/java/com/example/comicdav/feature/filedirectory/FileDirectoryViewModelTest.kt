package com.example.comicdav.feature.filedirectory

import com.example.comicdav.data.filedirectory.FileDirectoryCatalog
import com.example.comicdav.data.filedirectory.FileDirectorySourceEntity
import com.example.comicdav.data.filedirectory.FileDirectorySourceType
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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class FileDirectoryViewModelTest {
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
    fun addLocalDirectoryStoresManualSource() = runTest(dispatcher) {
        val catalog = FakeFileDirectoryCatalog()
        val reader = FakeLocalDirectoryReader()
        val viewModel = FileDirectoryViewModel(catalog, reader)

        viewModel.addLocalDirectory("Comics", "content://tree/comics")
        advanceUntilIdle()

        assertEquals(LocalDirectoryAdd("Comics", "content://tree/comics"), catalog.localAdds.single())
        assertEquals("已保存来源：Comics", viewModel.uiState.message)
    }

    @Test
    fun addWebDavDirectoryStoresConnectionDetails() = runTest(dispatcher) {
        val catalog = FakeFileDirectoryCatalog()
        val viewModel = FileDirectoryViewModel(catalog, FakeLocalDirectoryReader())

        viewModel.addWebDavDirectory(
            displayName = "/manga",
            accountId = "https://example.test/dav|lin",
            path = "/manga",
            baseUrl = "https://example.test/dav",
            username = "lin",
            password = "secret",
        )
        advanceUntilIdle()

        assertEquals(
            WebDavDirectoryAdd(
                displayName = "/manga",
                accountId = "https://example.test/dav|lin",
                path = "/manga",
                baseUrl = "https://example.test/dav",
                username = "lin",
                password = "secret",
            ),
            catalog.webDavAdds.single(),
        )
    }

    @Test
    fun openLocalSourceListsChildrenWithoutRecordingRecentAccess() = runTest(dispatcher) {
        val source = FileDirectorySourceEntity(
            id = 7L,
            displayName = "Comics",
            sourceType = FileDirectorySourceType.LOCAL,
            localTreeUri = "content://tree/comics",
            addedAt = 1L,
        )
        val catalog = FakeFileDirectoryCatalog(listOf(source))
        val reader = FakeLocalDirectoryReader(
            rootUri = "content://tree/comics/root",
            children = listOf(
                FileDirectoryBrowserItem("Series", "content://tree/comics/series", isDirectory = true),
                FileDirectoryBrowserItem("book.cbz", "content://tree/comics/book", isDirectory = false),
            ),
        )
        val viewModel = FileDirectoryViewModel(catalog, reader)
        advanceUntilIdle()

        viewModel.openLocalSource(source)
        advanceUntilIdle()

        assertFalse(viewModel.uiState.isLoading)
        assertEquals("Comics", viewModel.uiState.currentTitle)
        assertEquals(listOf("Series", "book.cbz"), viewModel.uiState.entries.map { it.name })
        assertEquals(emptyList<FileDirectorySourceEntity>(), catalog.recentWrites)
    }

    @Test
    fun handleBackFromNestedLocalDirectoryReturnsToParentDirectory() = runTest(dispatcher) {
        val source = FileDirectorySourceEntity(
            id = 7L,
            displayName = "Comics",
            sourceType = FileDirectorySourceType.LOCAL,
            localTreeUri = "content://tree/comics",
            addedAt = 1L,
        )
        val reader = FakeLocalDirectoryReader(
            rootUri = "content://tree/comics/root",
            childrenByUri = mapOf(
                "content://tree/comics/root" to listOf(
                    FileDirectoryBrowserItem("Series", "content://tree/comics/series", isDirectory = true),
                ),
                "content://tree/comics/series" to listOf(
                    FileDirectoryBrowserItem("book.cbz", "content://tree/comics/book", isDirectory = false),
                ),
            ),
        )
        val viewModel = FileDirectoryViewModel(FakeFileDirectoryCatalog(listOf(source)), reader)
        advanceUntilIdle()

        viewModel.openLocalSource(source)
        advanceUntilIdle()
        viewModel.openLocalDirectory(viewModel.uiState.entries.single())
        advanceUntilIdle()

        assertEquals("Series", viewModel.uiState.currentTitle)

        assertTrue(viewModel.handleBack())
        advanceUntilIdle()

        assertEquals("Comics", viewModel.uiState.currentTitle)
        assertEquals(listOf("Series"), viewModel.uiState.entries.map { it.name })
    }

    @Test
    fun handleBackFromLocalRootClosesLocalBrowser() = runTest(dispatcher) {
        val source = FileDirectorySourceEntity(
            id = 7L,
            displayName = "Comics",
            sourceType = FileDirectorySourceType.LOCAL,
            localTreeUri = "content://tree/comics",
            addedAt = 1L,
        )
        val viewModel = FileDirectoryViewModel(
            FakeFileDirectoryCatalog(listOf(source)),
            FakeLocalDirectoryReader(rootUri = "content://tree/comics/root"),
        )
        advanceUntilIdle()

        viewModel.openLocalSource(source)
        advanceUntilIdle()

        assertTrue(viewModel.handleBack())

        assertEquals(null, viewModel.uiState.currentTitle)
        assertTrue(viewModel.uiState.entries.isEmpty())
    }

    @Test
    fun handleBackFromSourceListIsNotHandled() = runTest(dispatcher) {
        val viewModel = FileDirectoryViewModel(FakeFileDirectoryCatalog(), FakeLocalDirectoryReader())
        advanceUntilIdle()

        assertFalse(viewModel.handleBack())
    }

    @Test
    fun deleteSourceRemovesSourceThroughCatalog() = runTest(dispatcher) {
        val catalog = FakeFileDirectoryCatalog()
        val viewModel = FileDirectoryViewModel(catalog, FakeLocalDirectoryReader())

        viewModel.deleteSource(42L)
        advanceUntilIdle()

        assertEquals(listOf(42L), catalog.deletedSourceIds)
        assertEquals("已删除来源", viewModel.uiState.message)
    }

    private class FakeFileDirectoryCatalog(
        initialSources: List<FileDirectorySourceEntity> = emptyList(),
    ) : FileDirectoryCatalog {
        private val sources = MutableStateFlow(initialSources)
        val localAdds = mutableListOf<LocalDirectoryAdd>()
        val webDavAdds = mutableListOf<WebDavDirectoryAdd>()
        val recentWrites = mutableListOf<FileDirectorySourceEntity>()
        val deletedSourceIds = mutableListOf<Long>()

        override fun observeSources(): Flow<List<FileDirectorySourceEntity>> = sources

        override suspend fun addLocalDirectory(displayName: String, treeUri: String): Long {
            localAdds += LocalDirectoryAdd(displayName, treeUri)
            return localAdds.size.toLong()
        }

        override suspend fun addWebDavDirectory(displayName: String, accountId: String, path: String): Long {
            webDavAdds += WebDavDirectoryAdd(displayName, accountId, path)
            return 1L
        }

        override suspend fun addWebDavDirectory(
            displayName: String,
            accountId: String,
            path: String,
            baseUrl: String,
            username: String,
            password: String,
        ): Long {
            webDavAdds += WebDavDirectoryAdd(displayName, accountId, path, baseUrl, username, password)
            return 1L
        }

        override suspend fun deleteSource(id: Long) {
            deletedSourceIds += id
        }
    }

    private class FakeLocalDirectoryReader(
        private val rootUri: String = "content://tree/root",
        private val children: List<FileDirectoryBrowserItem> = emptyList(),
        private val childrenByUri: Map<String, List<FileDirectoryBrowserItem>> = emptyMap(),
    ) : LocalDirectoryReader {
        override fun rootDocumentUri(treeUri: String): String = rootUri

        override suspend fun listChildren(documentUri: String): List<FileDirectoryBrowserItem> {
            return childrenByUri[documentUri] ?: children
        }
    }

    private data class LocalDirectoryAdd(
        val displayName: String,
        val treeUri: String,
    )

    private data class WebDavDirectoryAdd(
        val displayName: String,
        val accountId: String,
        val path: String,
        val baseUrl: String = "",
        val username: String = "",
        val password: String = "",
    )
}
