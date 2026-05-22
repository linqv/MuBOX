package com.example.comicdav.feature.videolibrary

import com.example.comicdav.data.videolibrary.VideoLibraryCatalog
import com.example.comicdav.data.videolibrary.VideoLibraryItemEntity
import com.example.comicdav.data.videolibrary.VideoLibraryItemWithSources
import com.example.comicdav.data.videolibrary.VideoSourceType
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

    private fun videoLibraryItem(
        id: Long,
        displayName: String,
    ): VideoLibraryItemWithSources {
        return VideoLibraryItemWithSources(
            item = VideoLibraryItemEntity(
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

        override suspend fun updateThumbnailPath(videoLibraryItemId: Long, thumbnailPath: String?) = Unit

        override suspend fun removeVideo(videoLibraryItemId: Long) = Unit
    }
}
