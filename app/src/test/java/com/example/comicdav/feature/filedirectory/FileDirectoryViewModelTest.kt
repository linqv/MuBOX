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
        assertEquals("Comics added to file directories", viewModel.uiState.message)
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

    private class FakeFileDirectoryCatalog(
        initialSources: List<FileDirectorySourceEntity> = emptyList(),
    ) : FileDirectoryCatalog {
        private val sources = MutableStateFlow(initialSources)
        val localAdds = mutableListOf<LocalDirectoryAdd>()
        val recentWrites = mutableListOf<FileDirectorySourceEntity>()

        override fun observeSources(): Flow<List<FileDirectorySourceEntity>> = sources

        override suspend fun addLocalDirectory(displayName: String, treeUri: String): Long {
            localAdds += LocalDirectoryAdd(displayName, treeUri)
            return localAdds.size.toLong()
        }

        override suspend fun addWebDavDirectory(displayName: String, accountId: String, path: String): Long {
            return 1L
        }
    }

    private class FakeLocalDirectoryReader(
        private val rootUri: String = "content://tree/root",
        private val children: List<FileDirectoryBrowserItem> = emptyList(),
    ) : LocalDirectoryReader {
        override fun rootDocumentUri(treeUri: String): String = rootUri

        override suspend fun listChildren(documentUri: String): List<FileDirectoryBrowserItem> = children
    }

    private data class LocalDirectoryAdd(
        val displayName: String,
        val treeUri: String,
    )
}
