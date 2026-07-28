package com.example.comicdav.feature.filedirectory

import com.example.comicdav.MainDispatcherRule
import com.example.comicdav.core.ports.FileDirectoryCatalog
import com.example.comicdav.core.model.source.FileDirectorySource
import com.example.comicdav.core.model.source.FileDirectorySourceType
import com.example.comicdav.feature.directorylisting.DirectoryListingViewMode
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class FileDirectoryViewModelTest {
    @get:Rule
    val mainDispatcher = MainDispatcherRule()

    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        mainDispatcher.set(dispatcher)
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
    fun gridModeAndExtractedVideoThumbnailAreKeptInBrowserState() = runTest(dispatcher) {
        val viewModel = FileDirectoryViewModel(
            FakeFileDirectoryCatalog(),
            FakeLocalDirectoryReader(),
        )
        advanceUntilIdle()

        viewModel.toggleViewMode()
        viewModel.onVideoThumbnailExtracted(
            uri = "content://videos/movie",
            version = "local:content://videos/movie:20:30",
            thumbnailPath = "/cache/movie.jpg",
        )

        assertEquals(DirectoryListingViewMode.GRID, viewModel.uiState.viewMode)
        assertEquals(
            "/cache/movie.jpg",
            viewModel.uiState.videoThumbnails["content://videos/movie"]?.path,
        )
        assertEquals(
            "local:content://videos/movie:20:30",
            viewModel.uiState.videoThumbnails["content://videos/movie"]?.version,
        )

        viewModel.onVideoThumbnailExtracted(
            uri = "content://videos/movie",
            version = "local:content://videos/movie:20:30",
            thumbnailPath = "/cache/movie.jpg",
        )

        assertEquals(
            2L,
            viewModel.uiState.videoThumbnails["content://videos/movie"]?.artworkRevision,
        )

        viewModel.closeLocalBrowser()

        assertTrue(viewModel.uiState.videoThumbnails.isEmpty())
    }

    @Test
    fun addWebDavDirectoryPassesOnlyAccountReferenceToCatalog() = runTest(dispatcher) {
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
            ),
            catalog.webDavAdds.single(),
        )
    }

    @Test
    fun openLocalSourceListsChildrenWithoutRecordingRecentAccess() = runTest(dispatcher) {
        val source = FileDirectorySource(
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
                FileDirectoryBrowserItem("book.cbz", "content://tree/comics/book-cbz", isDirectory = false),
                FileDirectoryBrowserItem("book.pdf", "content://tree/comics/book-pdf", isDirectory = false),
            ),
        )
        val viewModel = FileDirectoryViewModel(catalog, reader)
        advanceUntilIdle()

        viewModel.openLocalSource(source)
        advanceUntilIdle()

        assertFalse(viewModel.uiState.isLoading)
        assertEquals("Comics", viewModel.uiState.currentTitle)
        assertEquals(listOf("book.cbz", "book.pdf", "Series"), viewModel.uiState.entries.map { it.name })
        assertEquals(emptyList<FileDirectorySource>(), catalog.recentWrites)
    }

    @Test
    fun playbackDirectoryEntriesIgnoreSearchButPreserveDirectorySort() = runTest(dispatcher) {
        val source = FileDirectorySource(
            id = 7L,
            displayName = "Videos",
            sourceType = FileDirectorySourceType.LOCAL,
            localTreeUri = "content://tree/videos",
            addedAt = 1L,
        )
        val reader = FakeLocalDirectoryReader(
            rootUri = "content://tree/videos/root",
            children = listOf(
                FileDirectoryBrowserItem("Show E02.mkv", "content://videos/2", isDirectory = false),
                FileDirectoryBrowserItem("Show E01.mkv", "content://videos/1", isDirectory = false),
            ),
        )
        val viewModel = FileDirectoryViewModel(FakeFileDirectoryCatalog(listOf(source)), reader)
        advanceUntilIdle()
        viewModel.openLocalSource(source)
        advanceUntilIdle()

        viewModel.updateSearchQuery("E02")

        assertEquals(listOf("Show E02.mkv"), viewModel.uiState.entries.map { it.name })
        assertEquals(
            listOf("Show E01.mkv", "Show E02.mkv"),
            viewModel.playbackDirectoryEntries().map { it.name },
        )
    }

    @Test
    fun handleBackFromNestedLocalDirectoryReturnsToParentDirectory() = runTest(dispatcher) {
        val source = FileDirectorySource(
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
        val source = FileDirectorySource(
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

    @Test
    fun updateWebDavDirectoryPassesOnlyAccountReferenceToCatalog() = runTest(dispatcher) {
        val catalog = FakeFileDirectoryCatalog()
        val viewModel = FileDirectoryViewModel(catalog, FakeLocalDirectoryReader())

        viewModel.updateWebDavDirectory(
            id = 42L,
            displayName = "漫画库",
            accountId = "https://cloud.example.test:8443/dav|lin",
            path = "/manga/",
            baseUrl = "https://cloud.example.test:8443/dav",
            username = "lin",
            password = "secret",
        )
        advanceUntilIdle()

        assertEquals(
            WebDavDirectoryUpdate(
                id = 42L,
                displayName = "漫画库",
                accountId = "https://cloud.example.test:8443/dav|lin",
                path = "/manga/",
            ),
            catalog.webDavUpdates.single(),
        )
        assertEquals("已更新来源：漫画库", viewModel.uiState.message)
    }

    private class FakeFileDirectoryCatalog(
        initialSources: List<FileDirectorySource> = emptyList(),
    ) : FileDirectoryCatalog {
        private val sources = MutableStateFlow(initialSources)
        val localAdds = mutableListOf<LocalDirectoryAdd>()
        val webDavAdds = mutableListOf<WebDavDirectoryAdd>()
        val webDavUpdates = mutableListOf<WebDavDirectoryUpdate>()
        val recentWrites = mutableListOf<FileDirectorySource>()
        val deletedSourceIds = mutableListOf<Long>()

        override fun observeSources(): Flow<List<FileDirectorySource>> = sources

        override suspend fun addLocalDirectory(displayName: String, treeUri: String): Long {
            localAdds += LocalDirectoryAdd(displayName, treeUri)
            return localAdds.size.toLong()
        }

        override suspend fun addWebDavDirectory(displayName: String, accountId: String, path: String): Long {
            webDavAdds += WebDavDirectoryAdd(displayName, accountId, path)
            return 1L
        }

        override suspend fun deleteSource(id: Long) {
            deletedSourceIds += id
        }

        override suspend fun updateWebDavDirectory(
            id: Long,
            displayName: String,
            accountId: String,
            path: String,
        ) {
            webDavUpdates += WebDavDirectoryUpdate(id, displayName, accountId, path)
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
    )

    private data class WebDavDirectoryUpdate(
        val id: Long,
        val displayName: String,
        val accountId: String,
        val path: String,
    )
}
