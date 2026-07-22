package com.example.comicdav.feature.filedirectory

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import com.example.comicdav.MainDispatcherRule
import com.example.comicdav.data.AppColorPalette
import com.example.comicdav.data.filedirectory.FileDirectoryCatalog
import com.example.comicdav.data.filedirectory.FileDirectorySourceEntity
import com.example.comicdav.data.filedirectory.FileDirectorySourceType
import com.example.comicdav.ui.comicDavColorSchemeFor
import com.example.comicdav.ui.muBoxColorsFor
import com.example.comicdav.video.MediaKind
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
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
class FileDirectoryScreenTest {
    @get:Rule
    val mainDispatcher = MainDispatcherRule()

    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        mainDispatcher.set(dispatcher)
    }

    @Test
    fun screenColorsUseThemePaletteRoles() {
        val highContrast = comicDavColorSchemeFor(AppColorPalette.HIGH_CONTRAST)
        val colors = muBoxColorsFor(highContrast)

        assertEquals(highContrast.background, colors.background)
        assertEquals(highContrast.surfaceContainer, colors.panel)
        assertEquals(highContrast.surfaceContainerHigh, colors.panelHigh)
        assertEquals(highContrast.primary, colors.mediaAccent)
        assertEquals(highContrast.onPrimary, colors.onMediaAccent)
        assertEquals(highContrast.onBackground, colors.text)
        assertEquals(highContrast.onSurfaceVariant, colors.muted)
    }

    @Test
    fun sourceBadgeUsesAccessibleContrast() {
        val colors = muBoxColorsFor(comicDavColorSchemeFor(AppColorPalette.DEFAULT))

        assertTrue(
            "source badge contrast should meet AA for small text",
            contrastRatio(colors.onAccentSoft, colors.accentSoft) >= 4.5f,
        )
    }

    @Test
    fun entryTypeContentDescriptionsUseSharedMediaLabels() {
        MediaKind.entries.forEach { mediaKind ->
            assertEquals(
                com.example.comicdav.ui.muBoxMediaKindLabel(mediaKind),
                fileDirectoryEntryTypeContentDescription(mediaKind),
            )
        }
    }

    @Test
    fun comicLongPressActionsAddToLibrary() {
        val comic = FileDirectoryBrowserItem(
            name = "chapter.cbz",
            uri = "content://comic/chapter.cbz",
            isDirectory = false,
        )

        assertEquals(
            listOf(FileDirectoryEntryMenuAction.AddToLibrary),
            fileDirectoryEntryLongPressActions(comic),
        )
    }

    @Test
    fun videoLongPressActionsAddToVideoLibrary() {
        val video = FileDirectoryBrowserItem(
            name = "movie.mp4",
            uri = "content://video/movie.mp4",
            isDirectory = false,
        )

        assertEquals(
            listOf(FileDirectoryEntryMenuAction.AddToVideoLibrary),
            fileDirectoryEntryLongPressActions(video),
        )
    }

    @Test
    fun webDavSourceSubtitleDecodesPercentEncodedPath() {
        val source = FileDirectorySourceEntity(
            displayName = "漫画",
            sourceType = FileDirectorySourceType.WEBDAV,
            webDavPath = "/%E6%BC%AB%E7%94%BB/%E8%A7%86%E9%A2%91/",
            addedAt = 100L,
        )

        assertEquals("/漫画/视频/", fileDirectorySourceSubtitle(source))
    }

    @Test
    fun entryClickOpensDirectoriesAndReadsComics() {
        assertEquals(
            FileDirectoryEntryClickAction.OpenDirectory,
            fileDirectoryEntryClickAction(
                FileDirectoryBrowserItem("Series", "content://tree/comics/series", isDirectory = true),
            ),
        )
        assertEquals(
            FileDirectoryEntryClickAction.OpenComic,
            fileDirectoryEntryClickAction(
                FileDirectoryBrowserItem("book.cbz", "content://tree/comics/book", isDirectory = false),
            ),
        )
    }

    @Test
    fun directoryEntriesDoNotShowContinueBrowsingHint() {
        assertEquals(
            "",
            fileDirectoryEntrySupportingLabel(
                FileDirectoryBrowserItem("Series", "content://tree/comics/series", isDirectory = true),
            ),
        )
    }

    @Test
    fun comicEntriesOnlyShowSizeMetadata() {
        assertEquals(
            "4 KiB",
            fileDirectoryEntrySupportingLabel(
                FileDirectoryBrowserItem("book.cbz", "content://tree/comics/book", isDirectory = false, size = 4096L),
            ),
        )
        assertEquals(
            "大小未知",
            fileDirectoryEntrySupportingLabel(
                FileDirectoryBrowserItem("book.cbz", "content://tree/comics/book", isDirectory = false),
            ),
        )
    }

    @Test
    fun webDavSourcesCanBeEditedFromManagementActions() {
        val webDavSource = FileDirectorySourceEntity(
            id = 1L,
            displayName = "漫画库",
            sourceType = FileDirectorySourceType.WEBDAV,
            webDavAccountId = "https://example.test/dav|lin",
            webDavPath = "/manga",
            addedAt = 1L,
        )
        val localSource = webDavSource.copy(sourceType = FileDirectorySourceType.LOCAL, localTreeUri = "content://tree/comics")

        assertEquals(
            listOf(SourceManagementAction.EditWebDav, SourceManagementAction.DeleteSource),
            sourceManagementActions(webDavSource),
        )
        assertEquals(
            listOf(SourceManagementAction.RemoveSource, SourceManagementAction.DeleteLocalSourceWithFiles),
            sourceManagementActions(localSource),
        )
    }

    @Test
    fun refreshCurrentLocalDirectoryReloadsEntriesAndPreservesSearch() = runTest(dispatcher) {
        val source = localSource()
        val reader = MutableLocalDirectoryReader(
            children = listOf(
                FileDirectoryBrowserItem("Alpha.cbz", "content://tree/comics/alpha", isDirectory = false),
            ),
        )
        val viewModel = FileDirectoryViewModel(FakeFileDirectoryCatalog(), reader)
        advanceUntilIdle()
        viewModel.openLocalSource(source)
        advanceUntilIdle()
        viewModel.updateSearchQuery("Alpha")
        reader.children = listOf(
            FileDirectoryBrowserItem("Alpha 2.cbz", "content://tree/comics/alpha-2", isDirectory = false),
            FileDirectoryBrowserItem("Beta.cbz", "content://tree/comics/beta", isDirectory = false),
        )

        viewModel.refreshCurrentDirectory()

        assertTrue(viewModel.uiState.isRefreshing)
        assertEquals(listOf("Alpha.cbz"), viewModel.uiState.entries.map { it.name })
        advanceUntilIdle()

        assertFalse(viewModel.uiState.isRefreshing)
        assertEquals("Alpha", viewModel.uiState.searchQuery)
        assertEquals(listOf("Alpha 2.cbz"), viewModel.uiState.entries.map { it.name })
    }

    @Test
    fun refreshFailureKeepsCurrentLocalDirectoryEntries() = runTest(dispatcher) {
        val reader = MutableLocalDirectoryReader(
            children = listOf(
                FileDirectoryBrowserItem("book.cbz", "content://tree/comics/book", isDirectory = false),
            ),
        )
        val viewModel = FileDirectoryViewModel(FakeFileDirectoryCatalog(), reader)
        advanceUntilIdle()
        viewModel.openLocalSource(localSource())
        advanceUntilIdle()
        reader.failure = IllegalStateException("目录刷新失败")

        viewModel.refreshCurrentDirectory()
        advanceUntilIdle()

        assertFalse(viewModel.uiState.isRefreshing)
        assertEquals(listOf("book.cbz"), viewModel.uiState.entries.map { it.name })
        assertEquals("目录刷新失败", viewModel.uiState.error)
    }

    @Test
    fun localDirectoryBreadcrumbTracksTheFullNavigationPath() = runTest(dispatcher) {
        val nestedDirectory = FileDirectoryBrowserItem(
            name = "Series",
            uri = "content://tree/comics/series",
            isDirectory = true,
        )
        val viewModel = FileDirectoryViewModel(
            FakeFileDirectoryCatalog(),
            MutableLocalDirectoryReader(children = listOf(nestedDirectory)),
        )
        advanceUntilIdle()

        viewModel.openLocalSource(localSource())
        advanceUntilIdle()
        assertEquals(listOf("Comics"), viewModel.uiState.breadcrumbLabels)

        viewModel.openLocalDirectory(nestedDirectory)
        advanceUntilIdle()
        assertEquals(listOf("Comics", "Series"), viewModel.uiState.breadcrumbLabels)

        viewModel.goUp()
        advanceUntilIdle()
        assertEquals(listOf("Comics"), viewModel.uiState.breadcrumbLabels)

        viewModel.goUp()
        assertTrue(viewModel.uiState.breadcrumbLabels.isEmpty())
    }

    private fun contrastRatio(foreground: Color, background: Color): Float {
        val lighter = maxOf(foreground.luminance(), background.luminance())
        val darker = minOf(foreground.luminance(), background.luminance())
        return (lighter + 0.05f) / (darker + 0.05f)
    }

    private fun localSource() = FileDirectorySourceEntity(
        id = 7L,
        displayName = "Comics",
        sourceType = FileDirectorySourceType.LOCAL,
        localTreeUri = "content://tree/comics",
        addedAt = 1L,
    )

    private class FakeFileDirectoryCatalog : FileDirectoryCatalog {
        override fun observeSources(): Flow<List<FileDirectorySourceEntity>> = flowOf(emptyList())

        override suspend fun addLocalDirectory(displayName: String, treeUri: String): Long = 1L

        override suspend fun addWebDavDirectory(displayName: String, accountId: String, path: String): Long = 1L
    }

    private class MutableLocalDirectoryReader(
        var children: List<FileDirectoryBrowserItem>,
    ) : LocalDirectoryReader {
        var failure: Throwable? = null

        override fun rootDocumentUri(treeUri: String): String = "content://tree/comics/root"

        override suspend fun listChildren(documentUri: String): List<FileDirectoryBrowserItem> {
            failure?.let { throw it }
            return children
        }
    }
}
