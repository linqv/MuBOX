package com.example.comicdav.feature.videolibrary

import com.example.comicdav.core.ports.VideoLibraryCatalog
import com.example.comicdav.core.model.videolibrary.VideoLibraryItem
import com.example.comicdav.core.model.videolibrary.VideoLibraryItemWithSources
import com.example.comicdav.core.model.videolibrary.VideoSourceType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class VideoLibraryViewModelTest {
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
    fun observesCatalogItemsAndStopsLoading() = runTest(dispatcher) {
        val catalog = FakeVideoLibraryCatalog()
        val viewModel = VideoLibraryViewModel(catalog)

        advanceUntilIdle()

        assertTrue(viewModel.uiState.isLoading)

        val item = videoLibraryItem(id = 7L, displayName = "电影.mp4")
        catalog.emit(listOf(item))
        advanceUntilIdle()

        assertEquals(listOf(item), viewModel.uiState.items)
        assertFalse(viewModel.uiState.isLoading)
        assertNull(viewModel.uiState.error)
    }

    @Test
    fun clearsMessageAndErrorState() {
        val viewModel = VideoLibraryViewModel(FakeVideoLibraryCatalog())

        viewModel.showMessage("已加入影视库")
        viewModel.showError("添加失败")
        viewModel.clearMessage()

        assertNull(viewModel.uiState.message)
        assertNull(viewModel.uiState.error)
    }

    @Test
    fun tracksBatchThumbnailExtractionState() {
        val viewModel = VideoLibraryViewModel(FakeVideoLibraryCatalog())

        viewModel.showThumbnailExtractionResult("上一次提取完成")
        viewModel.setThumbnailExtractionInProgress(true)
        assertTrue(viewModel.uiState.isExtractingThumbnails)
        assertNull(viewModel.uiState.thumbnailExtractionMessage)

        viewModel.setThumbnailExtractionInProgress(false)
        assertFalse(viewModel.uiState.isExtractingThumbnails)
    }

    @Test
    fun publishesTransientThumbnailResultAndArtworkRevision() {
        val viewModel = VideoLibraryViewModel(FakeVideoLibraryCatalog())

        viewModel.onHistoryThumbnailExtracted("history-key")
        viewModel.showThumbnailExtractionResult(message = "1 个缩略图提取失败", isError = true)

        assertEquals(
            1L,
            viewModel.uiState.thumbnailArtworkRevisions.history["history-key"],
        )
        assertEquals("1 个缩略图提取失败", viewModel.uiState.thumbnailExtractionMessage)
        assertTrue(viewModel.uiState.thumbnailExtractionMessageIsError)

        viewModel.clearThumbnailExtractionMessage()

        assertNull(viewModel.uiState.thumbnailExtractionMessage)
        assertFalse(viewModel.uiState.thumbnailExtractionMessageIsError)
    }

    @Test
    fun extractedVideoThumbnailUpdatesTheMatchingCardImmediately() = runTest(dispatcher) {
        val catalog = FakeVideoLibraryCatalog()
        val viewModel = VideoLibraryViewModel(catalog)
        val first = videoLibraryItem(id = 1L, displayName = "第一部")
        val second = videoLibraryItem(id = 2L, displayName = "第二部")

        advanceUntilIdle()
        catalog.emit(listOf(first, second))
        advanceUntilIdle()
        viewModel.onVideoThumbnailExtracted(
            videoLibraryItemId = first.item.id,
            thumbnailPath = "/cache/first.jpg",
        )

        assertEquals("/cache/first.jpg", viewModel.uiState.items[0].item.thumbnailPath)
        assertNull(viewModel.uiState.items[1].item.thumbnailPath)
        assertEquals(
            1L,
            viewModel.uiState.thumbnailArtworkRevisions.videos[first.item.id],
        )
        assertEquals(1L, viewModel.uiState.thumbnailArtworkRevisions.sharedVideoArtwork)
    }

    @Test
    fun sharedVideoRevisionInvalidatesHistoryForEverySharedCacheWrite() {
        val viewModel = VideoLibraryViewModel(FakeVideoLibraryCatalog())

        viewModel.onHistoryThumbnailExtracted("video-history", isVideo = true)
        viewModel.onSharedVideoThumbnailExtracted()

        assertEquals(
            1L,
            viewModel.uiState.thumbnailArtworkRevisions.history["video-history"],
        )
        assertEquals(2L, viewModel.uiState.thumbnailArtworkRevisions.sharedVideoArtwork)
    }

    private fun videoLibraryItem(
        id: Long,
        displayName: String,
    ): VideoLibraryItemWithSources {
        return VideoLibraryItemWithSources(
            item = VideoLibraryItem(
                id = id,
                title = displayName,
                displayName = displayName,
                sourceType = VideoSourceType.LOCAL,
                addedAt = 100L,
            ),
            localSource = null,
            webDavSource = null,
        )
    }

    private class FakeVideoLibraryCatalog : VideoLibraryCatalog {
        private val items = MutableSharedFlow<List<VideoLibraryItemWithSources>>(replay = 0)

        override fun observeVideoLibrary(): Flow<List<VideoLibraryItemWithSources>> = items

        suspend fun emit(value: List<VideoLibraryItemWithSources>) {
            items.emit(value)
        }

        override suspend fun addLocalVideo(
            uri: String,
            fileName: String,
            size: Long?,
            lastModified: Long?,
            thumbnailPath: String?,
        ): Long = 1L

        override suspend fun addWebDavVideo(
            accountId: String,
            remotePath: String,
            fileName: String,
            size: Long?,
            etag: String?,
            lastModified: Long?,
            thumbnailPath: String?,
        ): Long = 1L

        override suspend fun markOpened(videoLibraryItemId: Long) = Unit

        override suspend fun synchronizeLocalVideoThumbnail(
            videoLibraryItemId: Long,
            fileName: String,
            size: Long?,
            lastModified: Long?,
            thumbnailPath: String,
        ) = Unit

        override suspend fun synchronizeWebDavVideoThumbnail(
            videoLibraryItemId: Long,
            fileName: String,
            size: Long?,
            etag: String?,
            lastModified: Long?,
            thumbnailPath: String,
        ) = Unit

        override suspend fun updateThumbnailPath(videoLibraryItemId: Long, thumbnailPath: String?) = Unit

        override suspend fun removeVideo(videoLibraryItemId: Long) = Unit
    }
}
